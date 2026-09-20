package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
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

import cds.gen.insurance.InspectionFindings;
import cds.gen.insurance.InspectionFindings_;
import cds.gen.insurance.Inspections;
import cds.gen.insurance.Inspections_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * Underwriter review of a completed inspection report.
 *
 * Spec: specs/underwriting/inspections/review-inspection-findings.feature.md
 */
@Component
public class InspectionReviewManager {

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public InspectionReviewManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Report confirms the rated exposure" — findings matching the application
     * are accepted, the policy continues unchanged, and the review closes with a
     * "no action required" disposition.
     *
     * "Replacement cost is materially understated" — an inspected replacement
     * cost exceeding the rated limit by more than the 10% tolerance increases
     * the dwelling limit at the next anniversary on 45 days notice.
     *
     * "Undisclosed trampoline changes the liability decision" — an observed
     * trampoline attaches a liability exclusion endorsement at the next
     * anniversary, removable by documenting safety netting and fencing.
     */
    public InspectionFindings review(String inspectionId, Integer squareFeet, Integer roofAgeYears,
            BigDecimal inspectedReplacementCost, String reviewedBy, String observedHazard) {
        Inspections inspection = loadInspection(inspectionId);
        Policies policy = loadPolicy(inspection.getPolicyId());
        LocalDate today = LocalDate.now(clock);

        BigDecimal ratedLimit = policy.getDwellingCoverage() == null
                ? BigDecimal.ZERO : policy.getDwellingCoverage();

        InspectionFindings findings = InspectionFindings.create();
        findings.setId(UUID.randomUUID().toString());
        findings.setInspectionId(inspectionId);
        findings.setPolicyId(policy.getId());
        findings.setSquareFeet(squareFeet);
        findings.setRoofAgeYears(roofAgeYears);
        findings.setInspectedReplacementCost(inspectedReplacementCost);
        findings.setRatedDwellingLimit(ratedLimit);
        findings.setReviewedBy(reviewedBy);
        findings.setReviewedOn(today);

        BigDecimal variancePct = variancePct(ratedLimit, inspectedReplacementCost);
        findings.setVariancePct(variancePct);

        boolean materiallyUnderstated =
                variancePct.compareTo(Statuses.RCV_VARIANCE_TOLERANCE_PCT) > 0;
        boolean trampoline = observedHazard != null
                && observedHazard.toLowerCase().contains("trampoline");

        if (materiallyUnderstated) {
            findings.setDisposition(Statuses.DISPOSITION_LIMIT_INCREASE);
            findings.setLimitIncreasedTo(inspectedReplacementCost);
            findings.setChangeEffectiveOn(policy.getExpirationDate());
            findings.setNoticeDays(Statuses.COVERAGE_CHANGE_NOTICE_DAYS);
            email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                    "Change to your dwelling coverage",
                    "An inspection valued your dwelling at " + inspectedReplacementCost
                            + ". Your dwelling limit increases to that value effective "
                            + policy.getExpirationDate() + ", with "
                            + Statuses.COVERAGE_CHANGE_NOTICE_DAYS + " days notice."));
        } else if (trampoline) {
            findings.setDisposition(Statuses.DISPOSITION_EXCLUSION);
            findings.setExclusionEndorsement(Statuses.EXCLUSION_TRAMPOLINE);
            findings.setChangeEffectiveOn(policy.getExpirationDate());
            findings.setExclusionRemovableBy(
                    "documenting safety netting and fencing around the trampoline");
        } else {
            findings.setDisposition(Statuses.DISPOSITION_NO_ACTION);
        }

        persistence.run(Insert.into(InspectionFindings_.class).entry(findings));

        inspection.setStatus(Statuses.INSPECTION_COMPLETED);
        inspection.setCompletedOn(today);
        inspection.setFindingsId(findings.getId());
        persistence.run(Update.entity(Inspections_.class).entry(inspection));

        return load(findings.getId());
    }

    /**
     * "Unrepaired hazard leads to non-renewal" — a hazard not corrected within
     * the 60-day cure period non-renews the policy for an uncorrected hazardous
     * condition, with the report photographs retained with the notice.
     */
    public InspectionFindings nonRenewForUncorrectedHazard(String findingsId) {
        InspectionFindings findings = load(findingsId);
        findings.setDisposition(Statuses.DISPOSITION_NON_RENEWAL);
        findings.setNonRenewalReason(Statuses.NON_RENEWAL_HAZARD);
        findings.setPhotosRetained(true);
        persistence.run(Update.entity(InspectionFindings_.class).entry(findings));
        return load(findingsId);
    }

    /** Percentage by which the inspected value exceeds the rated limit. */
    private BigDecimal variancePct(BigDecimal ratedLimit, BigDecimal inspectedValue) {
        if (ratedLimit == null || inspectedValue == null
                || ratedLimit.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return inspectedValue.subtract(ratedLimit)
                .multiply(BigDecimal.valueOf(100))
                .divide(ratedLimit, 2, RoundingMode.HALF_UP);
    }

    public InspectionFindings load(String findingsId) {
        return persistence.run(Select.from(InspectionFindings_.class)
                .where(f -> f.ID().eq(findingsId)))
                .first(InspectionFindings.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Inspection findings not found: " + findingsId));
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
