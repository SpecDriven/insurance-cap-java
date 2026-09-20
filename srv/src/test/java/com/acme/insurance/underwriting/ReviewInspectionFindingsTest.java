package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.InspectionFindings;
import cds.gen.insurance.Inspections;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/underwriting/inspections/review-inspection-findings.feature.md
 * "Insured disputes the inspected square footage" and "Report belongs to the
 * wrong address" are [proposed] and intentionally absent.
 */
@DisplayName("Review Inspection Findings — specs/underwriting/inspections/review-inspection-findings.feature.md")
class ReviewInspectionFindingsTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Report confirms the rated exposure @v1")
    void reportConfirmsTheRatedExposure() {
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        Inspections inspection = orderInspection(policy);

        // A completed inspection showing 2,340 square feet and a 6-year-old roof,
        // with a replacement cost matching the rated limit
        InspectionFindings findings = db(() -> reviews.review(inspection.getId(), 2340, 6,
                new BigDecimal("480000.00"), "Priya Raman", null));

        // The findings are accepted and the policy continues unchanged
        assertThat(findings.getSquareFeet()).isEqualTo(2340);
        assertThat(findings.getRoofAgeYears()).isEqualTo(6);
        assertThat(db(() -> reloadPolicy(policy.getId())).getDwellingCoverage())
                .isEqualByComparingTo(new BigDecimal("480000.00"));

        // The review is closed with a "no action required" disposition
        assertThat(findings.getDisposition()).isEqualTo(Statuses.DISPOSITION_NO_ACTION);
        assertThat(db(() -> inspections.load(inspection.getId())).getStatus())
                .isEqualTo(Statuses.INSPECTION_COMPLETED);
    }

    @Test
    @DisplayName("Replacement cost is materially understated @v1")
    void replacementCostIsMateriallyUnderstated() {
        // A rated dwelling limit of $480,000 and an inspected replacement cost of $612,000
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        Inspections inspection = orderInspection(policy);
        emails.reset();

        InspectionFindings findings = db(() -> reviews.review(inspection.getId(), 2900, 6,
                new BigDecimal("612000.00"), "Priya Raman", null));

        // The variance exceeds the 10% tolerance
        assertThat(findings.getVariancePct()).isEqualByComparingTo(new BigDecimal("27.50"));

        // The dwelling limit is increased to the inspected value at the next anniversary
        assertThat(findings.getDisposition()).isEqualTo(Statuses.DISPOSITION_LIMIT_INCREASE);
        assertThat(findings.getLimitIncreasedTo())
                .isEqualByComparingTo(new BigDecimal("612000.00"));
        assertThat(findings.getChangeEffectiveOn()).isEqualTo(policy.getExpirationDate());

        // The insured receives 45 days notice of the coverage and premium change
        assertThat(findings.getNoticeDays()).isEqualTo(Statuses.COVERAGE_CHANGE_NOTICE_DAYS);
        assertThat(emailSubjects()).contains("Change to your dwelling coverage");
    }

    @Test
    @DisplayName("Undisclosed trampoline changes the liability decision @v1")
    void undisclosedTrampolineChangesTheLiabilityDecision() {
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        Inspections inspection = orderInspection(policy);

        // An inspection photograph showing an unfenced trampoline
        InspectionFindings findings = db(() -> reviews.review(inspection.getId(), 2340, 6,
                new BigDecimal("480000.00"), "Priya Raman", "unfenced trampoline in rear yard"));

        // A trampoline liability exclusion endorsement is added at the next anniversary
        assertThat(findings.getDisposition()).isEqualTo(Statuses.DISPOSITION_EXCLUSION);
        assertThat(findings.getExclusionEndorsement()).isEqualTo(Statuses.EXCLUSION_TRAMPOLINE);
        assertThat(findings.getChangeEffectiveOn()).isEqualTo(policy.getExpirationDate());

        // The insured may remove the exclusion by documenting safety netting and fencing
        assertThat(findings.getExclusionRemovableBy())
                .contains("safety netting")
                .contains("fencing");
    }

    @Test
    @DisplayName("Unrepaired hazard leads to non-renewal @v1")
    void unrepairedHazardLeadsToNonRenewal() {
        Policies policy = insertHomePolicy(p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        Inspections inspection = orderInspection(policy);

        // An inspection documenting a deteriorated deck with missing guardrails
        InspectionFindings findings = db(() -> reviews.review(inspection.getId(), 2340, 6,
                new BigDecimal("480000.00"), "Priya Raman", "deteriorated deck, missing guardrails"));

        // The hazard is not corrected within the 60-day cure period
        clock.setBusinessDate(BASE_DATE.plusDays(Statuses.HAZARD_CURE_DAYS + 1));
        InspectionFindings nonRenewed =
                db(() -> reviews.nonRenewForUncorrectedHazard(findings.getId()));

        // The policy is non-renewed for an uncorrected hazardous condition
        assertThat(nonRenewed.getDisposition()).isEqualTo(Statuses.DISPOSITION_NON_RENEWAL);
        assertThat(nonRenewed.getNonRenewalReason()).isEqualTo(Statuses.NON_RENEWAL_HAZARD);

        // Photographs from the report are retained with the notice
        assertThat(nonRenewed.getPhotosRetained()).isTrue();
    }

    private Inspections orderInspection(Policies policy) {
        return db(() -> inspections.order(policy.getId(), "Lone Star Inspections",
                "+1-555-0148", 0));
    }
}
