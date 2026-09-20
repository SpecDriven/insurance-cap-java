package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.InspectionRecommendations;
import cds.gen.insurance.InspectionRecommendations_;
import cds.gen.insurance.Inspections;
import cds.gen.insurance.Inspections_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.RecommendationProofs;

/**
 * Recommendations raised by an inspection or survey, tracked to closure with a
 * severity, a cure period, and a consequence if the period is not met.
 *
 * Spec: specs/underwriting/inspections/track-inspection-recommendations.feature.md
 */
@Component
public class RecommendationManager {

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public RecommendationManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Critical recommendation opens with a 30-day cure period" — knob-and-tube
     * wiring opens at critical severity, starting a 30-day cure period with
     * written notice to the insured, and flags the policy for non-renewal if the
     * period lapses.
     *
     * Advisory recommendations open with the same cure period but no coverage
     * consequence when it expires.
     */
    public InspectionRecommendations open(String inspectionId, String severity,
            String description) {
        Inspections inspection = loadInspection(inspectionId);
        LocalDate today = LocalDate.now(clock);
        boolean critical = Statuses.SEVERITY_CRITICAL.equalsIgnoreCase(severity);
        int cureDays = critical ? Statuses.CRITICAL_CURE_DAYS : Statuses.ADVISORY_CURE_DAYS;

        InspectionRecommendations recommendation = InspectionRecommendations.create();
        recommendation.setId(UUID.randomUUID().toString());
        recommendation.setInspectionId(inspectionId);
        recommendation.setPolicyId(inspection.getPolicyId());
        recommendation.setSeverity(critical ? Statuses.SEVERITY_CRITICAL : Statuses.SEVERITY_ADVISORY);
        recommendation.setDescription(description);
        recommendation.setStatus(Statuses.RECOMMENDATION_OPEN);
        recommendation.setOpenedOn(today);
        recommendation.setCurePeriodDays(cureDays);
        recommendation.setCureDueOn(today.plusDays(cureDays));
        recommendation.setNonRenewalFlagged(false);
        recommendation.setCarriedForward(false);
        persistence.run(Insert.into(InspectionRecommendations_.class).entry(recommendation));

        if (critical && inspection.getPolicyId() != null) {
            Policies policy = loadPolicy(inspection.getPolicyId());
            recommendation.setInsuredNotifiedInWriting(true);
            persistence.run(Update.entity(InspectionRecommendations_.class).entry(recommendation));
            email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                    "Required correction on your property",
                    "An inspection identified: " + description + ". This must be corrected within "
                            + cureDays + " days, by " + recommendation.getCureDueOn()
                            + ", or the policy will be flagged for non-renewal."));
        }

        return load(recommendation.getId());
    }

    /**
     * "Insured submits proof of completion" — a dated photograph and contractor
     * invoice close the recommendation as complete and clear the non-renewal
     * flag from the policy.
     */
    public InspectionRecommendations submitProof(String recommendationId, String documentType,
            String fileName, LocalDate documentDate) {
        InspectionRecommendations recommendation = load(recommendationId);

        RecommendationProofs proof = RecommendationProofs.create();
        proof.setId(UUID.randomUUID().toString());
        proof.setDocumentType(documentType);
        proof.setFileName(fileName);
        proof.setDocumentDate(documentDate);

        List<RecommendationProofs> proofs = new ArrayList<>(
                recommendation.getProofDocuments() == null
                        ? List.of() : recommendation.getProofDocuments());
        proofs.add(proof);

        recommendation.setProofDocuments(proofs);
        recommendation.setStatus(Statuses.RECOMMENDATION_COMPLETE);
        recommendation.setClosedOn(LocalDate.now(clock));
        recommendation.setNonRenewalFlagged(false);
        persistence.run(Update.entity(InspectionRecommendations_.class).entry(recommendation));

        return load(recommendationId);
    }

    /**
     * The cure-period monitor.
     *
     * "Advisory recommendations do not affect the policy" — an advisory
     * recommendation whose cure period expires without action closes as declined
     * with no coverage consequence, carried forward as context on the next
     * inspection.
     *
     * A critical recommendation that lapses flags the policy for non-renewal.
     */
    public List<InspectionRecommendations> expireCurePeriods() {
        LocalDate today = LocalDate.now(clock);
        List<InspectionRecommendations> open = persistence
                .run(Select.from(InspectionRecommendations_.class)
                        .where(r -> r.status().eq(Statuses.RECOMMENDATION_OPEN)))
                .listOf(InspectionRecommendations.class);

        List<InspectionRecommendations> expired = new ArrayList<>();
        for (InspectionRecommendations recommendation : open) {
            if (recommendation.getCureDueOn() == null
                    || today.isBefore(recommendation.getCureDueOn())) {
                continue;
            }
            if (Statuses.SEVERITY_ADVISORY.equals(recommendation.getSeverity())) {
                recommendation.setStatus(Statuses.RECOMMENDATION_DECLINED);
                recommendation.setClosedOn(today);
                recommendation.setClosureReason("declined — advisory, no coverage consequence");
                recommendation.setCarriedForward(true);
            } else {
                recommendation.setNonRenewalFlagged(true);
            }
            persistence.run(Update.entity(InspectionRecommendations_.class).entry(recommendation));
            expired.add(load(recommendation.getId()));
        }
        return expired;
    }

    public InspectionRecommendations load(String recommendationId) {
        return persistence.run(Select.from(InspectionRecommendations_.class)
                .where(r -> r.ID().eq(recommendationId))
                .columns(r -> r._all(), r -> r.proofDocuments().expand()))
                .first(InspectionRecommendations.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Recommendation not found: " + recommendationId));
    }

    private Inspections loadInspection(String inspectionId) {
        return persistence.run(Select.from(Inspections_.class)
                .where(i -> i.ID().eq(inspectionId)))
                .first(Inspections.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Inspection not found: " + inspectionId));
    }

    private Policies loadPolicy(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));
    }
}
