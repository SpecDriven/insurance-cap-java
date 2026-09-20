package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.ReferralReasons;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/referrals/refer-to-underwriter.feature.md
 * "Agent can add context before the underwriter opens it", "Quote expires while
 * the referral is open", and "Referral on a risk the underwriter cannot bind"
 * are [proposed] and intentionally absent.
 */
@DisplayName("Refer Submission to Underwriter — specs/underwriting/referrals/refer-to-underwriter.feature.md")
class ReferToUnderwriterTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Submission referred for a single rule failure @v1")
    void submissionReferredForASingleRuleFailure() {
        Submissions submission = newHomeSubmission();
        // Fails the loss frequency rule during screening
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));

        Referrals referral = db(() -> referrals.refer(submission.getId(),
                Statuses.REFERRAL_TYPE_STANDARD, "Texas property"));

        // The submission moves to the underwriting queue in "referred" status
        assertThat(referral.getStatus()).isEqualTo(Statuses.REFERRAL_REFERRED);
        assertThat(db(() -> eligibility.load(submission.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_REFERRED);

        // The failing rule and its evaluated values are attached to the referral
        assertThat(referral.getReasons()).hasSize(1);
        assertThat(referral.getReasons().get(0).getReasonCode())
                .isEqualTo(Statuses.RULE_LOSS_HISTORY);
        assertThat(referral.getReasons().get(0).getEvaluatedValue()).isNotBlank();
    }

    @Test
    @DisplayName("Multiple reasons are consolidated into one referral @v1")
    void multipleReasonsAreConsolidatedIntoOneReferral() {
        Submissions submission = newHomeSubmission();
        // Fails the insurance score rule and carries an over-limit request
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, false, true, true)));

        Referrals referral = db(() -> referrals.refer(submission.getId(),
                Statuses.REFERRAL_TYPE_STANDARD, "Texas property"));

        // A single referral lists both reasons in priority order
        assertThat(referral.getReasons()).hasSize(2);
        assertThat(referral.getReasons())
                .extracting(ReferralReasons::getPriority)
                .containsExactly(1, 2);

        // Duplicate referrals for the same submission are suppressed
        Referrals second = db(() -> referrals.refer(submission.getId(),
                Statuses.REFERRAL_TYPE_STANDARD, "Texas property"));
        assertThat(second.getId()).isEqualTo(referral.getId());
    }

    @Test
    @DisplayName("Referral routed by line of business and state @v1")
    void referralRoutedByLineOfBusinessAndState() {
        Submissions submission = newHomeSubmission();
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));

        // A homeowners submission for a risk located in Texas
        Referrals referral = db(() -> referrals.refer(submission.getId(),
                Statuses.REFERRAL_TYPE_STANDARD, "Texas property"));

        // It is routed to the property team licensed in Texas
        assertThat(referral.getQueueName()).isEqualTo("Texas property");

        // The referral SLA clock starts
        assertThat(referral.getQueuedAt()).isNotNull();
        assertThat(referral.getSlaStatus()).isEqualTo(Statuses.SLA_OPEN);
        assertThat(referral.getSlaTargetHours()).isEqualTo(8);
        assertThat(referral.getSlaDueAt()).isNotNull();
    }

    @Test
    @DisplayName("No underwriter available in the routed queue @v1")
    void noUnderwriterAvailableInTheRoutedQueue() {
        Submissions submission = newHomeSubmission();
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));

        // The Texas property queue has no available underwriter
        clock.setBusinessDateTime(MONDAY, LocalTime.of(9, 0));
        Referrals referral = db(() -> referrals.refer(submission.getId(),
                Statuses.REFERRAL_TYPE_STANDARD, "Texas property"));

        // The referral cannot be assigned within 4 hours
        clock.setBusinessDateTime(MONDAY, LocalTime.of(13, 30));
        db(() -> referrals.runSlaMonitor());

        // It is reassigned to the overflow queue
        Referrals overflowed = db(() -> referrals.load(referral.getId()));
        assertThat(overflowed.getQueueName()).isEqualTo(Statuses.QUEUE_OVERFLOW);

        // The reassignment is logged with the original routing decision
        assertThat(overflowed.getOriginalQueueName()).isEqualTo("Texas property");
        assertThat(overflowed.getReassignmentReason()).contains("Texas property");
    }

    /** A plain Monday, clear of weekends and holidays. */
    private static final java.time.LocalDate MONDAY = java.time.LocalDate.of(2026, 1, 12);
}
