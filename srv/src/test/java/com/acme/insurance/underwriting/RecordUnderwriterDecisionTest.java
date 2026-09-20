package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.CounterOffers;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.UnderwriterDecisions;

/**
 * Spec: specs/underwriting/referrals/record-underwriter-decision.feature.md
 * "Decision reversed within the same business day", "Decline notice delivery
 * and DOI record retention", and "Decline requires a reason code and free text"
 * are [proposed] and intentionally absent.
 */
@DisplayName("Record Underwriter Decision — specs/underwriting/referrals/record-underwriter-decision.feature.md")
class RecordUnderwriterDecisionTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Accept as submitted releases the quote @v1")
    void acceptAsSubmittedReleasesTheQuote() {
        Referrals referral = openReferral();

        UnderwriterDecisions decision = db(() -> decisions.record(referral.getId(),
                Statuses.DECISION_ACCEPT, "Dana Whitfield", null,
                "All requested information on file", null, null, null, null));

        // The submission returns to the agent as a bindable quote
        assertThat(db(() -> eligibility.load(referral.getSubmissionId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_BINDABLE);

        // The decision, underwriter name, and timestamp are written to the file
        assertThat(decision.getOutcome()).isEqualTo(Statuses.DECISION_ACCEPT);
        assertThat(decision.getUnderwriterName()).isEqualTo("Dana Whitfield");
        assertThat(decision.getDecidedAt()).isNotNull();
        assertThat(db(() -> referrals.load(referral.getId())).getStatus())
                .isEqualTo(Statuses.REFERRAL_DECIDED);
    }

    @Test
    @DisplayName("Accept with conditions attaches requirements to the bind @v1")
    void acceptWithConditionsAttachesRequirementsToTheBind() {
        Referrals referral = openReferral();

        // The underwriter accepts subject to a roof inspection within 60 days
        UnderwriterDecisions decision = db(() -> decisions.record(referral.getId(),
                Statuses.DECISION_ACCEPT_WITH_CONDITIONS, "Dana Whitfield", null,
                "24-year-old roof", "Roof inspection", 60, null, null));

        // The condition is attached to the quote and printed on the binder
        assertThat(decision.getConditions()).hasSize(1);
        assertThat(decision.getConditions().get(0).getDescription()).isEqualTo("Roof inspection");
        assertThat(decision.getConditions().get(0).getDueInDays()).isEqualTo(60);
        assertThat(decision.getConditions().get(0).getPrintedOnBinder()).isTrue();

        // The inspection order is created
        assertThat(db(() -> persistence.run(com.sap.cds.ql.Select
                .from(cds.gen.insurance.Inspections_.class)
                .where(i -> i.submission_ID().eq(referral.getSubmissionId())))
                .listOf(cds.gen.insurance.Inspections.class))).hasSize(1);
    }

    @Test
    @DisplayName("Counter-offer with a higher deductible @v1")
    void counterOfferWithAHigherDeductible() {
        Referrals referral = openReferral();

        // The underwriter counters with a 2% named storm deductible
        UnderwriterDecisions decision = db(() -> decisions.record(referral.getId(),
                Statuses.DECISION_COUNTER_OFFER, "Dana Whitfield", null,
                "Coastal territory", null, null, "2% named storm deductible",
                new BigDecimal("1875.00")));

        // The counter-offer is presented to the agent with the revised premium
        CounterOffers counter = db(() -> decisions.counterOfferFor(decision.getId()));
        assertThat(counter.getDescription()).isEqualTo("2% named storm deductible");
        assertThat(counter.getRevisedPremium()).isEqualByComparingTo(new BigDecimal("1875.00"));

        // The original quote remains available for 5 days for comparison
        assertThat(counter.getOriginalQuoteValidUntil())
                .isEqualTo(BASE_DATE.plusDays(Statuses.COUNTER_OFFER_COMPARISON_DAYS));
    }

    private Referrals openReferral() {
        Submissions submission = newHomeSubmission();
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));
        return db(() -> referrals.refer(submission.getId(), Statuses.REFERRAL_TYPE_STANDARD,
                "Texas property"));
    }
}
