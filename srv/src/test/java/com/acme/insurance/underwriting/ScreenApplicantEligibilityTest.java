package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.EligibilityScreenings;
import cds.gen.insurance.ScreeningRuleResults;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
 * "Applicant residing outside the writing state", "Business-use exposure on a
 * personal application", and "Third-party data vendor is unavailable" are
 * [proposed] and intentionally absent.
 */
@DisplayName("Screen Applicant Eligibility — specs/underwriting/eligibility/screen-applicant-eligibility.feature.md")
class ScreenApplicantEligibilityTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Clean applicant clears screening automatically @v1")
    void cleanApplicantClearsScreeningAutomatically() {
        Submissions submission = newHomeSubmission();

        EligibilityScreenings screening = db(() ->
                eligibility.screen(submission.getId(), allRulesPass()));

        // The submission is marked eligible and rating proceeds without human review
        assertThat(screening.getOutcome()).isEqualTo(Statuses.SUBMISSION_ELIGIBLE);
        assertThat(db(() -> eligibility.load(submission.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_ELIGIBLE);

        // The screening result is stamped with the rule set version used
        assertThat(screening.getRuleSetVersion()).isEqualTo(Statuses.SCREENING_RULE_SET_VERSION);
        assertThat(screening.getResults())
                .allSatisfy(r -> assertThat(r.getPassed()).isTrue());
    }

    @Test
    @DisplayName("Unlicensed driver on the application @v1")
    void unlicensedDriverOnTheApplication() {
        Submissions submission = newHomeSubmission();

        EligibilityScreenings screening = db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(false, true, true, true, true)));

        // The submission is declined
        assertThat(screening.getOutcome()).isEqualTo(Statuses.SUBMISSION_DECLINED);

        // The decline reason "no valid driver license" is recorded on the file
        Submissions declined = db(() -> eligibility.load(submission.getId()));
        assertThat(declined.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);
        assertThat(declined.getDeclineReason()).isEqualTo(Statuses.DECLINE_NO_LICENSE);
    }

    @Test
    @DisplayName("Applicant fails two of four screening checks @v1")
    void applicantFailsTwoOfFourScreeningChecks() {
        Submissions submission = newHomeSubmission();

        // Fails the insurance score and prior cancellation checks
        EligibilityScreenings screening = db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, true, false, false, true)));

        // The submission is referred rather than auto-declined
        assertThat(screening.getOutcome()).isEqualTo(Statuses.SUBMISSION_REFERRED);

        // Both failed rules are listed on the referral
        assertThat(db(() -> eligibility.failedRuleNames(
                eligibility.loadScreening(screening.getId()))))
                .containsExactly(Statuses.RULE_INSURANCE_SCORE, Statuses.RULE_PRIOR_CANCELLATION);

        // Both are underwriter-overridable per the rule table
        assertThat(screening.getResults().stream()
                .filter(r -> !r.getPassed())
                .map(ScreeningRuleResults::getOverridable))
                .containsOnly(true);
    }

    @Test
    @DisplayName("Screening result is valid for 60 days @v1")
    void screeningResultIsValidFor60Days() {
        Submissions submission = newHomeSubmission();

        EligibilityScreenings first = db(() ->
                eligibility.screen(submission.getId(), allRulesPass()));
        assertThat(first.getExpiresOn())
                .isEqualTo(BASE_DATE.plusDays(EligibilityManager.SCREENING_VALID_DAYS));

        // The same submission is rebound after the result has gone stale
        clock.setBusinessDate(BASE_DATE.plusDays(77));
        EligibilityScreenings rerun = db(() ->
                eligibility.screen(submission.getId(), allRulesPass()));

        // The stale result is discarded and the new result replaces it on the file
        assertThat(db(() -> eligibility.loadScreening(first.getId())).getSuperseded()).isTrue();
        assertThat(rerun.getSuperseded()).isFalse();
        assertThat(rerun.getId()).isNotEqualTo(first.getId());
        assertThat(rerun.getScreenedOn()).isEqualTo(BASE_DATE.plusDays(77));
    }
}
