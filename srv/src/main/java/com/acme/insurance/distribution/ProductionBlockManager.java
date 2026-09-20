package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.domain.SystemContextRunner;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.ComplianceOverrides;
import cds.gen.insurance.ComplianceOverrides_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.Producers;
import cds.gen.insurance.ProductionBlocks;
import cds.gen.insurance.ProductionBlocks_;

/**
 * The licence gate at bind: writing through an unlicensed or unappointed
 * producer is refused and logged, with a narrow compliance override.
 *
 * Spec: specs/distribution/producer-licensing/block-unlicensed-production.feature.md
 */
@Component
public class ProductionBlockManager {

    private final PersistenceService persistence;
    private final ProducerLicensingManager licensing;
    private final CommissionManager commissions;
    private final SystemContextRunner system;
    private final Clock clock;

    public ProductionBlockManager(PersistenceService persistence,
            ProducerLicensingManager licensing, CommissionManager commissions,
            SystemContextRunner system, Clock clock) {
        this.persistence = persistence;
        this.licensing = licensing;
        this.commissions = commissions;
        this.system = system;
        this.clock = clock;
    }

    /**
     * "Unlicensed producer cannot bind" — a producer with no active licence in
     * the state of the risk is refused, and the attempt is written to the
     * compliance log with the producer number and state.
     *
     * "Emergency override requires compliance approval" — an unexpired override
     * lets the bind through exactly once, and is consumed in the process.
     */
    public ProductionBlocks attemptBind(String producerId, String riskState, String policyId) {
        Producers producer = licensing.load(producerId);
        LocalDate today = LocalDate.now(clock);
        boolean authorized = licensing.isAuthorized(producerId, riskState);

        ProductionBlocks attempt = ProductionBlocks.create();
        attempt.setId(UUID.randomUUID().toString());
        attempt.setProducerId(producerId);
        attempt.setAgencyId(producer.getAgencyId());
        attempt.setPolicyId(policyId);
        attempt.setState(riskState);
        attempt.setAttemptedAction(Statuses.ACTION_BIND);
        attempt.setProducerNumber(producer.getNpn());
        attempt.setLoggedOn(today);

        if (authorized) {
            attempt.setBlocked(false);
        } else {
            ComplianceOverrides override = activeOverrideFor(producerId);
            if (override != null) {
                // Permitted once; the override is consumed here.
                override.setUsesRemaining(override.getUsesRemaining() - 1);
                persistence.run(Update.entity(ComplianceOverrides_.class).entry(override));

                attempt.setBlocked(false);
                attempt.setComplianceOverrideId(override.getId());
                attempt.setReason("permitted under compliance override " + override.getId());
            } else {
                attempt.setBlocked(true);
                attempt.setReason(Statuses.BLOCK_NO_ACTIVE_LICENSE);
            }
        }

        if (Boolean.TRUE.equals(attempt.getBlocked())) {
            // The compliance log must survive the refusal, so the row is written
            // in its own change set before the exception rolls this one back.
            ProductionBlocks logged = attempt;
            system.run(() -> persistence.run(Insert.into(ProductionBlocks_.class).entry(logged)));
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "Bind refused: producer " + producer.getNpn() + " has "
                            + Statuses.BLOCK_NO_ACTIVE_LICENSE + " (" + riskState + ")");
        }
        persistence.run(Insert.into(ProductionBlocks_.class).entry(attempt));
        if (policyId != null) {
            stampProducerOfRecord(policyId, producerId);
        }
        return load(attempt.getId());
    }

    /**
     * "Agency principal may bind on behalf of an unlicensed staff member" — a
     * licensed principal reviewing and binding an application assembled by an
     * unlicensed customer service representative succeeds under the principal's
     * licence, and the principal is recorded as the producer of record.
     */
    public ProductionBlocks bindOnBehalfOfStaff(String principalProducerId, String riskState,
            String policyId) {
        Producers principal = licensing.load(principalProducerId);
        if (!licensing.isAuthorized(principalProducerId, riskState)) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "The agency principal " + principal.getNpn()
                            + " is not licensed in " + riskState);
        }
        // The bind runs under the principal's own licence, so it takes the
        // ordinary authorized path and records the principal as producer.
        return attemptBind(principalProducerId, riskState, policyId);
    }

    /**
     * "Emergency override requires compliance approval" — a compliance officer
     * approves a 72-hour override with documented evidence; the bind is permitted
     * once and the override expires automatically after 72 hours.
     */
    public ComplianceOverrides approveOverride(String producerId, String approvedBy,
            String evidence) {
        licensing.load(producerId);
        Instant now = clock.instant();

        ComplianceOverrides override = ComplianceOverrides.create();
        override.setId(UUID.randomUUID().toString());
        override.setProducerId(producerId);
        override.setApprovedBy(approvedBy);
        override.setEvidence(evidence);
        override.setApprovedAt(now);
        override.setExpiresAt(now.plus(Statuses.OVERRIDE_HOURS, ChronoUnit.HOURS));
        override.setUsesRemaining(1);
        persistence.run(Insert.into(ComplianceOverrides_.class).entry(override));

        return loadOverride(override.getId());
    }

    /**
     * "Commission is withheld on business written without authority" — commission
     * on a policy bound by a producer whose licence had lapsed is withheld
     * pending compliance review, while the policy itself remains in force to
     * protect the insured.
     */
    public List<cds.gen.insurance.CommissionEntries> withholdCommission(String policyId) {
        return commissions.withhold(policyId, Statuses.WITHHELD_PENDING_COMPLIANCE);
    }

    /** Blocked attempts logged against an agency, for the compliance log. */
    public List<ProductionBlocks> blockedAttemptsFor(String agencyId) {
        return persistence.run(Select.from(ProductionBlocks_.class)
                .where(b -> b.agency_ID().eq(agencyId)))
                .listOf(ProductionBlocks.class).stream()
                .filter(b -> Boolean.TRUE.equals(b.getBlocked()))
                .toList();
    }

    /** An approved, unexpired, unused override for the producer, if any. */
    private ComplianceOverrides activeOverrideFor(String producerId) {
        Instant now = clock.instant();
        return persistence.run(Select.from(ComplianceOverrides_.class)
                .where(o -> o.producer_ID().eq(producerId)))
                .listOf(ComplianceOverrides.class).stream()
                .filter(o -> o.getUsesRemaining() != null && o.getUsesRemaining() > 0)
                .filter(o -> o.getExpiresAt() != null && now.isBefore(o.getExpiresAt()))
                .findFirst()
                .orElse(null);
    }

    private void stampProducerOfRecord(String policyId, String producerId) {
        persistence.run(Select.from(Policies_.class).where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .ifPresent(policy -> {
                    policy.setProducerOfRecordId(producerId);
                    persistence.run(Update.entity(Policies_.class).entry(policy));
                });
    }

    public ProductionBlocks load(String blockId) {
        return persistence.run(Select.from(ProductionBlocks_.class)
                .where(b -> b.ID().eq(blockId)))
                .first(ProductionBlocks.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Production block not found: " + blockId));
    }

    public ComplianceOverrides loadOverride(String overrideId) {
        return persistence.run(Select.from(ComplianceOverrides_.class)
                .where(o -> o.ID().eq(overrideId)))
                .first(ComplianceOverrides.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Override not found: " + overrideId));
    }
}
