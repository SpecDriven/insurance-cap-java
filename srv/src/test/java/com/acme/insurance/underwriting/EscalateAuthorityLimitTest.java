package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.AuthorityEscalations;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/referrals/escalate-authority-limit.feature.md
 * "Approval granted after the quote is already bound", "Approver is the same
 * person who referred the file", and "Escalation during a catastrophe
 * moratorium" are [proposed] and intentionally absent.
 */
@DisplayName("Escalate Beyond Authority Limit — specs/underwriting/referrals/escalate-authority-limit.feature.md")
class EscalateAuthorityLimitTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Limit above the underwriter's authority escalates @v1")
    void limitAboveTheUnderwritersAuthorityEscalates() {
        Referrals referral = openReferral();

        // An underwriter with $5,000,000 of personal lines authority; a
        // $10,000,000 umbrella limit is requested on the referral
        assertThat(db(() -> escalations.exceedsAuthority(Statuses.ROLE_UNDERWRITER,
                new BigDecimal("10000000.00")))).isTrue();

        AuthorityEscalations escalation = db(() -> escalations.escalate(referral.getId(),
                Statuses.ESCALATION_LIMIT, new BigDecimal("10000000.00"), null,
                Statuses.ROLE_UNDERWRITER, "Recommend approval", "Approve", null));

        // The file escalates to the regional underwriting manager
        assertThat(escalation.getEscalatedTo()).isEqualTo(Statuses.ROLE_REGIONAL_MANAGER);
        assertThat(escalation.getAuthorityLimit())
                .isEqualByComparingTo(new BigDecimal("5000000.00"));
        assertThat(db(() -> referrals.load(referral.getId())).getStatus())
                .isEqualTo(Statuses.REFERRAL_ESCALATED);

        // The underwriter may recommend but not approve the request
        assertThat(escalation.getRecommendedOutcome()).isEqualTo("Approve");
        assertThat(escalation.getStatus()).isEqualTo(Statuses.ESCALATION_OPEN);
    }

    @Test
    @DisplayName("Discretionary credit above the permitted band @v1")
    void discretionaryCreditAboveThePermittedBand() {
        Referrals referral = openReferral();

        // A schedule rating credit band of plus or minus 15% at the underwriter
        // level; a 25% credit is requested to meet a competitor's price
        assertThat(db(() -> escalations.exceedsCreditBand(Statuses.ROLE_UNDERWRITER,
                new BigDecimal("25.00")))).isTrue();

        AuthorityEscalations escalation = db(() -> escalations.escalate(referral.getId(),
                Statuses.ESCALATION_CREDIT, null, new BigDecimal("25.00"),
                Statuses.ROLE_UNDERWRITER, "Match competitor", "Approve",
                "Competitor quoted 22% below our filed rate"));

        // The credit request escalates for approval
        assertThat(escalation.getEscalationType()).isEqualTo(Statuses.ESCALATION_CREDIT);
        assertThat(escalation.getPermittedCreditPct())
                .isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(escalation.getEscalatedTo()).isEqualTo(Statuses.ROLE_REGIONAL_MANAGER);

        // The competitive justification must be documented before approval
        assertThat(escalation.getJustification()).isNotBlank();
    }

    @Test
    @DisplayName("Recommendation carried forward to the approver @v1")
    void recommendationCarriedForwardToTheApprover() {
        Referrals referral = openReferral();

        // An escalated file with the underwriter's written recommendation to approve
        AuthorityEscalations escalation = db(() -> escalations.escalate(referral.getId(),
                Statuses.ESCALATION_LIMIT, new BigDecimal("10000000.00"), null,
                Statuses.ROLE_UNDERWRITER,
                "Long-standing account, no losses in 7 years; recommend approval",
                "Approve", null));

        // The recommendation, supporting documents, and rule failures are
        // presented together
        assertThat(escalation.getRecommendation()).contains("recommend approval");
        assertThat(db(() -> referrals.load(referral.getId())).getReasons()).isNotEmpty();

        // The approver may accept, modify, or reject the recommendation
        assertThat(escalation.getApproverMayDecide()).isTrue();
    }

    private Referrals openReferral() {
        Submissions submission = newHomeSubmission();
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));
        return db(() -> referrals.refer(submission.getId(), Statuses.REFERRAL_TYPE_OVER_LIMIT,
                "Texas property"));
    }
}
