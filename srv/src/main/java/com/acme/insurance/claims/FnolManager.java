package com.acme.insurance.claims;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.NumberRanges;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.ClaimPhotos;
import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * First notice of loss.
 *
 * Spec: specs/claims/auto-accidents/record-car-accident.feature.md
 * Spec: specs/claims/home-claims/record-water-damage-claim.feature.md
 */
@Component
public class FnolManager {

    static final String MITIGATION_VENDOR = "RapidDry Mitigation";

    private final PersistenceService persistence;
    private final NumberRanges numbers;
    private final EmailPort email;
    private final Clock clock;

    public FnolManager(PersistenceService persistence, NumberRanges numbers, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.numbers = numbers;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Report accident online" / "Report accident through the claims hotline":
     * both channels open the claim identically — a unique claim number, status
     * "Open — awaiting adjuster", and a confirmation email with the claim
     * number. The hotline agent reads the returned claim number back to the
     * caller.
     */
    public Claims reportAutoAccident(String policyId, LocalDate dateOfLoss, String lossLocation,
            String description, Collection<String> photos, String channel) {

        Policies policy = loadInForcePolicy(policyId, Statuses.LOB_AUTO);

        Claims claim = Claims.create();
        claim.setId(UUID.randomUUID().toString());
        claim.setClaimNumber(numbers.nextClaimNumber());
        claim.setPolicyId(policy.getId());
        claim.setClaimType(Statuses.CLAIM_TYPE_AUTO_COLLISION);
        claim.setStatus(Statuses.CLAIM_OPEN_AWAITING_ADJUSTER);
        claim.setChannel(channel);
        claim.setDateOfLoss(dateOfLoss);
        claim.setLossLocation(lossLocation);
        claim.setDescription(description);
        claim.setDeductible(policy.getDeductible());

        List<ClaimPhotos> photoRows = new ArrayList<>();
        if (photos != null) {
            for (String fileName : photos) {
                ClaimPhotos photo = ClaimPhotos.create();
                photo.setId(UUID.randomUUID().toString());
                photo.setFileName(fileName);
                photoRows.add(photo);
            }
        }
        claim.setPhotos(photoRows);

        persistence.run(Insert.into(Claims_.class).entry(claim));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Claim " + claim.getClaimNumber() + " received",
                "We opened claim " + claim.getClaimNumber() + " for your accident on " + dateOfLoss
                        + ". An adjuster will be assigned shortly."));

        return readClaim(claim.getId());
    }

    /**
     * "Burst pipe is covered": a burst-pipe loss on an in-force HO-3 policy
     * opens a claim classified "sudden and accidental water damage", and an
     * emergency mitigation vendor is offered within 24 hours (we offer one
     * immediately at FNOL). Gradual-leak and surface-flood handling are
     * [proposed] and stay absent.
     */
    public Claims reportWaterDamage(String policyId, LocalDate dateOfLoss, String description,
            String causeOfLoss) {

        Policies policy = loadInForcePolicy(policyId, Statuses.LOB_HOME);

        Claims claim = Claims.create();
        claim.setId(UUID.randomUUID().toString());
        claim.setClaimNumber(numbers.nextClaimNumber());
        claim.setPolicyId(policy.getId());
        claim.setClaimType(Statuses.CLAIM_TYPE_HOME_WATER);
        claim.setStatus(Statuses.CLAIM_OPEN_AWAITING_ADJUSTER);
        claim.setChannel(Statuses.CHANNEL_ONLINE);
        claim.setDateOfLoss(dateOfLoss);
        claim.setDescription(description);
        claim.setDeductible(policy.getDeductible());

        if (isBurstPipe(causeOfLoss)) {
            claim.setCoverageClassification(Statuses.COVERAGE_SUDDEN_ACCIDENTAL_WATER);
        }

        claim.setMitigationVendorName(MITIGATION_VENDOR);
        claim.setMitigationVendorOfferedAt(clock.instant());

        persistence.run(Insert.into(Claims_.class).entry(claim));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Claim " + claim.getClaimNumber() + " received",
                "We opened claim " + claim.getClaimNumber() + " for the water damage on " + dateOfLoss + "."));
        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Emergency mitigation for claim " + claim.getClaimNumber(),
                MITIGATION_VENDOR + " can start emergency water mitigation at your home within 24 hours."
                        + " Reply or call the claims hotline to schedule."));

        return readClaim(claim.getId());
    }

    private boolean isBurstPipe(String causeOfLoss) {
        return causeOfLoss != null && causeOfLoss.toLowerCase(Locale.ROOT).contains("burst");
    }

    private Policies loadInForcePolicy(String policyId, String lineOfBusiness) {
        Policies policy = persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Policy not found"));
        if (!lineOfBusiness.equals(policy.getLineOfBusiness())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "The policy is not a " + lineOfBusiness.toLowerCase(Locale.ROOT) + " policy");
        }
        if (!Statuses.POLICY_IN_FORCE.equals(policy.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The policy is not in force");
        }
        return policy;
    }

    public Claims readClaim(String claimId) {
        return persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claimId))
                .columns(c -> c._all(), c -> c.photos().expand()))
                .single(Claims.class);
    }
}
