package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.InspectionRecommendations;
import cds.gen.insurance.Inspections;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/underwriting/inspections/track-inspection-recommendations.feature.md
 * "Extension granted for a contractor backlog", "Partial completion keeps the
 * recommendation open", and "Recommendation reopens after a related claim" are
 * [proposed] and intentionally absent.
 */
@DisplayName("Track Inspection Recommendations — specs/underwriting/inspections/track-inspection-recommendations.feature.md")
class TrackInspectionRecommendationsTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Critical recommendation opens with a 30-day cure period @v1")
    void criticalRecommendationOpensWithA30DayCurePeriod() {
        Inspections inspection = orderInspection();
        emails.reset();

        // An inspection citing knob-and-tube wiring in an occupied dwelling
        InspectionRecommendations recommendation = db(() -> recommendations.open(
                inspection.getId(), Statuses.SEVERITY_CRITICAL,
                "Knob-and-tube wiring in an occupied dwelling"));

        // A 30-day cure period starts
        assertThat(recommendation.getSeverity()).isEqualTo(Statuses.SEVERITY_CRITICAL);
        assertThat(recommendation.getCurePeriodDays()).isEqualTo(Statuses.CRITICAL_CURE_DAYS);
        assertThat(recommendation.getCureDueOn())
                .isEqualTo(BASE_DATE.plusDays(Statuses.CRITICAL_CURE_DAYS));

        // The insured is notified in writing
        assertThat(recommendation.getInsuredNotifiedInWriting()).isTrue();
        assertThat(emailSubjects()).contains("Required correction on your property");

        // The policy is flagged for non-renewal if the cure period lapses
        assertThat(recommendation.getNonRenewalFlagged()).isFalse();
        clock.setBusinessDate(BASE_DATE.plusDays(Statuses.CRITICAL_CURE_DAYS + 1));
        db(() -> recommendations.expireCurePeriods());
        assertThat(db(() -> recommendations.load(recommendation.getId())).getNonRenewalFlagged())
                .isTrue();
    }

    @Test
    @DisplayName("Insured submits proof of completion @v1")
    void insuredSubmitsProofOfCompletion() {
        Inspections inspection = orderInspection();

        // An open recommendation to install handrails on an exterior stair
        InspectionRecommendations recommendation = db(() -> recommendations.open(
                inspection.getId(), Statuses.SEVERITY_CRITICAL,
                "Install handrails on the exterior stair"));

        // The insured uploads a dated photograph and a contractor invoice
        db(() -> recommendations.submitProof(recommendation.getId(), "Photograph",
                "handrail-installed.jpg", BASE_DATE.plusDays(12)));
        InspectionRecommendations closed = db(() -> recommendations.submitProof(
                recommendation.getId(), "Invoice", "invoice-8841.pdf", BASE_DATE.plusDays(12)));

        // The recommendation is closed as complete
        assertThat(closed.getStatus()).isEqualTo(Statuses.RECOMMENDATION_COMPLETE);
        assertThat(closed.getClosedOn()).isEqualTo(BASE_DATE);
        assertThat(closed.getProofDocuments()).hasSize(2);

        // The non-renewal flag is cleared from the policy
        assertThat(closed.getNonRenewalFlagged()).isFalse();
    }

    @Test
    @DisplayName("Advisory recommendations do not affect the policy @v1")
    void advisoryRecommendationsDoNotAffectThePolicy() {
        Inspections inspection = orderInspection();

        // An advisory recommendation to trim vegetation away from the siding
        InspectionRecommendations recommendation = db(() -> recommendations.open(
                inspection.getId(), Statuses.SEVERITY_ADVISORY,
                "Trim vegetation away from the siding"));

        // The cure period expires without action
        clock.setBusinessDate(BASE_DATE.plusDays(Statuses.ADVISORY_CURE_DAYS + 1));
        db(() -> recommendations.expireCurePeriods());

        InspectionRecommendations expired =
                db(() -> recommendations.load(recommendation.getId()));

        // The recommendation is closed as declined with no coverage consequence
        assertThat(expired.getStatus()).isEqualTo(Statuses.RECOMMENDATION_DECLINED);
        assertThat(expired.getClosureReason()).contains("no coverage consequence");
        assertThat(expired.getNonRenewalFlagged()).isFalse();

        // It is carried forward as context on the next inspection
        assertThat(expired.getCarriedForward()).isTrue();
    }

    private Inspections orderInspection() {
        Policies policy = insertHomePolicy(
                p -> p.setDwellingCoverage(new BigDecimal("480000.00")));
        return db(() -> inspections.order(policy.getId(), "Lone Star Inspections",
                "+1-555-0148", 0));
    }
}
