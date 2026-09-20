package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.PriorCoverageHistories;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/eligibility/detect-prior-cancellation.feature.md
 * "Conflicting prior carrier records" and "Non-renewal following a catastrophe
 * is set aside" are [proposed] and intentionally absent.
 */
@DisplayName("Detect Prior Cancellation — specs/underwriting/eligibility/detect-prior-cancellation.feature.md")
class DetectPriorCancellationTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Gap in coverage longer than 30 days @v1")
    void gapInCoverageLongerThan30Days() {
        Submissions submission = newHomeSubmission();

        // Prior coverage ended January 9, requested effective date March 2
        PriorCoverageHistories history = db(() -> priorCoverage.evaluate(submission.getId(),
                "Prior Mutual", LocalDate.of(2026, 1, 9), LocalDate.of(2026, 3, 2),
                Statuses.PRIOR_INSURED_REQUESTED, 0, null));

        assertThat(history.getLapseDays()).isEqualTo(52);

        // The continuous-coverage discount is removed
        assertThat(history.getContinuousCoverageDiscount()).isFalse();

        // The submission is rated at the no-prior-insurance tier
        assertThat(history.getRatingTier()).isEqualTo(Statuses.TIER_NO_PRIOR_INSURANCE);
    }

    @Test
    @DisplayName("Insured-initiated cancellation is not disqualifying @v1")
    void insuredInitiatedCancellationIsNotDisqualifying() {
        Submissions submission = newHomeSubmission();

        // A prior policy cancelled at the insured's request after selling the vehicle
        PriorCoverageHistories history = db(() -> priorCoverage.evaluate(submission.getId(),
                "Prior Mutual", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15),
                Statuses.PRIOR_INSURED_REQUESTED, 0, LocalDate.of(2026, 1, 1)));

        // Classified as insured-requested and does not affect eligibility
        assertThat(history.getClassification()).isEqualTo(Statuses.PRIOR_INSURED_REQUESTED);
        assertThat(db(() -> eligibility.load(submission.getId()).getStatus()))
                .isEqualTo(Statuses.SUBMISSION_SCREENING);
    }

    @Test
    @DisplayName("Non-payment cancellation within the last year @v1")
    void nonPaymentCancellationWithinTheLastYear() {
        Submissions submission = newHomeSubmission();

        // Two cancellations for non-payment of premium in the past 12 months
        PriorCoverageHistories history = db(() -> priorCoverage.evaluate(submission.getId(),
                "Prior Mutual", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10),
                Statuses.PRIOR_NON_PAYMENT, 2, LocalDate.of(2025, 11, 4)));

        // The submission is referred and full payment in advance is required to bind
        assertThat(db(() -> eligibility.load(submission.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_REFERRED);
        assertThat(history.getPaymentInAdvanceRequired()).isTrue();

        // The installment plan option is suppressed on the quote
        assertThat(history.getInstallmentsSuppressed()).isTrue();
    }

    @Test
    @DisplayName("Prior cancellation for material misrepresentation @v1")
    void priorCancellationForMaterialMisrepresentation() {
        Submissions submission = newHomeSubmission();
        LocalDate cancelledOn = LocalDate.of(2025, 8, 19);

        db(() -> priorCoverage.evaluate(submission.getId(), "Prior Mutual",
                LocalDate.of(2025, 8, 19), LocalDate.of(2026, 1, 20),
                Statuses.PRIOR_MISREPRESENTATION, 0, cancelledOn));

        // The submission is declined
        Submissions declined = db(() -> eligibility.load(submission.getId()));
        assertThat(declined.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);

        // The prior carrier name and cancellation date are recorded on the decline
        assertThat(declined.getDeclineReason())
                .contains("Prior Mutual")
                .contains(cancelledOn.toString());
    }
}
