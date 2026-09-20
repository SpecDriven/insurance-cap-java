package com.acme.insurance.distribution;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.BusinessHours;
import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.AgencyTiers;
import cds.gen.insurance.AgencyTiers_;
import cds.gen.insurance.Agencies_;

/**
 * Agency onboarding: the packet, the duplicate-tax-ID check, the regulatory
 * review, and the production commitment that sets the starting tier.
 *
 * Spec: specs/distribution/agency-management/onboard-agency.feature.md
 */
@Component
public class AgencyOnboardingManager {

    private final PersistenceService persistence;
    private final Clock clock;

    public AgencyOnboardingManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Agency submits a complete onboarding packet" — a prospective Ohio agency
     * with an active resident licence, $1M/$3M E&amp;O, and a signed W-9 is created
     * in pending-appointment status with a distribution manager assigned to
     * review within 5 business days.
     *
     * "Duplicate tax identification number is rejected" — a second packet under a
     * tax ID already on an appointed agency is rejected as a duplicate and the
     * submitter is directed to add a branch location instead.
     */
    public Agencies submitPacket(String legalName, String taxId, String domicileState,
            Boolean residentLicenseActive, BigDecimal eoPerClaimLimit,
            BigDecimal eoAggregateLimit, Boolean w9Signed, String principalName,
            String principalEmail) {

        existingByTaxId(taxId).ifPresent(existing -> {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Duplicate tax identification number " + taxId
                            + " — add a branch location to the existing agency "
                            + existing.getLegalName() + " instead.");
        });

        LocalDate today = LocalDate.now(clock);

        Agencies agency = Agencies.create();
        agency.setId(UUID.randomUUID().toString());
        agency.setLegalName(legalName);
        agency.setTaxId(taxId);
        agency.setDomicileState(domicileState);
        agency.setResidentLicenseState(domicileState);
        agency.setResidentLicenseActive(Boolean.TRUE.equals(residentLicenseActive));
        agency.setEoPerClaimLimit(eoPerClaimLimit);
        agency.setEoAggregateLimit(eoAggregateLimit);
        agency.setW9Signed(Boolean.TRUE.equals(w9Signed));
        agency.setPrincipalName(principalName);
        agency.setPrincipalEmail(principalEmail);
        agency.setStatus(Statuses.AGENCY_PENDING_APPOINTMENT);
        agency.setSubmittedOn(today);
        agency.setReviewerName("Distribution Manager");
        agency.setReviewDueOn(plusBusinessDays(today, Statuses.AGENCY_REVIEW_BUSINESS_DAYS));

        persistence.run(Insert.into(Agencies_.class).entry(agency));
        return load(agency.getId());
    }

    /**
     * "Principal with a prior license revocation is declined" — the background and
     * regulatory review declines onboarding and records "adverse regulatory
     * history" on the agency record.
     */
    public Agencies completeRegulatoryReview(String agencyId, Boolean priorLicenseRevocation) {
        Agencies agency = load(agencyId);
        if (Boolean.TRUE.equals(priorLicenseRevocation)) {
            agency.setStatus(Statuses.AGENCY_DECLINED);
            agency.setDeclineReason(Statuses.DECLINE_ADVERSE_REGULATORY);
            persistence.run(Update.entity(Agencies_.class).entry(agency));
        }
        return load(agencyId);
    }

    /**
     * "Production commitment sets the initial agency tier" — a $750,000 projection
     * lands in the Silver band, and the tier drives the starting commission
     * schedule (set-commission-schedule.feature.md).
     */
    public Agencies confirmProductionCommitment(String agencyId, BigDecimal projectedNewPremium) {
        Agencies agency = load(agencyId);
        agency.setProjectedNewPremium(projectedNewPremium);
        agency.setTier(tierFor(projectedNewPremium).getTierName());
        persistence.run(Update.entity(Agencies_.class).entry(agency));
        return load(agencyId);
    }

    /** The tier band containing the projected premium; see the table in the spec. */
    public AgencyTiers tierFor(BigDecimal projectedNewPremium) {
        List<AgencyTiers> tiers = persistence.run(Select.from(AgencyTiers_.class))
                .listOf(AgencyTiers.class);
        return tiers.stream()
                .filter(t -> t.getMinProjectedPremium() == null
                        || projectedNewPremium.compareTo(t.getMinProjectedPremium()) >= 0)
                .filter(t -> t.getMaxProjectedPremium() == null
                        || projectedNewPremium.compareTo(t.getMaxProjectedPremium()) <= 0)
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.SERVER_ERROR,
                        "No agency tier band covers " + projectedNewPremium));
    }

    public Agencies load(String agencyId) {
        return persistence.run(Select.from(Agencies_.class).where(a -> a.ID().eq(agencyId)))
                .first(Agencies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Agency not found: " + agencyId));
    }

    private java.util.Optional<Agencies> existingByTaxId(String taxId) {
        return persistence.run(Select.from(Agencies_.class).where(a -> a.taxId().eq(taxId)))
                .listOf(Agencies.class).stream()
                .filter(a -> !Statuses.AGENCY_DECLINED.equals(a.getStatus()))
                .findFirst();
    }

    private static LocalDate plusBusinessDays(LocalDate from, int days) {
        LocalDate cursor = from;
        int remaining = days;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (BusinessHours.isBusinessDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }
}
