package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.CounterOffers;
import cds.gen.insurance.CounterOffers_;
import cds.gen.insurance.DecisionConditions;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Referrals_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;
import cds.gen.insurance.UnderwriterDecisions;
import cds.gen.insurance.UnderwriterDecisions_;

/**
 * Records the outcome of a referral and the notices each outcome requires.
 *
 * Spec: specs/underwriting/referrals/record-underwriter-decision.feature.md
 */
@Component
public class UnderwriterDecisionManager {

    private final PersistenceService persistence;
    private final ReferralManager referrals;
    private final InspectionManager inspections;
    private final Clock clock;

    public UnderwriterDecisionManager(PersistenceService persistence, ReferralManager referrals,
            InspectionManager inspections, Clock clock) {
        this.persistence = persistence;
        this.referrals = referrals;
        this.inspections = inspections;
        this.clock = clock;
    }

    /**
     * "Accept as submitted releases the quote" — the submission returns to the
     * agent as a bindable quote, and the decision, underwriter name, and
     * timestamp are written to the file.
     *
     * "Accept with conditions attaches requirements to the bind" — the condition
     * is attached to the quote and printed on the binder, and the inspection
     * order is created.
     *
     * "Counter-offer with a higher deductible" — the counter-offer is presented
     * with the revised premium, and the original quote remains available for 5
     * days for comparison.
     */
    public UnderwriterDecisions record(String referralId, String outcome, String underwriterName,
            String reasonCode, String narrative, String conditionDescription,
            Integer conditionDueInDays, String counterOfferDescription, BigDecimal revisedPremium) {
        Referrals referral = referrals.load(referralId);
        Submissions submission = loadSubmission(referral.getSubmissionId());
        LocalDateTime decidedAt = LocalDateTime.now(clock);

        UnderwriterDecisions decision = UnderwriterDecisions.create();
        decision.setId(UUID.randomUUID().toString());
        decision.setReferralId(referralId);
        decision.setOutcome(outcome);
        decision.setUnderwriterName(underwriterName);
        decision.setDecidedAt(decidedAt.atZone(clock.getZone()).toInstant());
        decision.setReasonCode(reasonCode);
        decision.setNarrative(narrative);

        if (Statuses.DECISION_ACCEPT_WITH_CONDITIONS.equals(outcome)) {
            DecisionConditions condition = DecisionConditions.create();
            condition.setId(UUID.randomUUID().toString());
            condition.setDescription(conditionDescription);
            condition.setDueInDays(conditionDueInDays);
            condition.setPrintedOnBinder(true);
            List<DecisionConditions> conditions = new ArrayList<>();
            conditions.add(condition);
            decision.setConditions(conditions);
        }

        persistence.run(Insert.into(UnderwriterDecisions_.class).entry(decision));

        if (Statuses.DECISION_COUNTER_OFFER.equals(outcome)) {
            CounterOffers counter = CounterOffers.create();
            counter.setId(UUID.randomUUID().toString());
            counter.setDecisionId(decision.getId());
            counter.setDescription(counterOfferDescription);
            counter.setRevisedPremium(revisedPremium);
            // The original quote remains available for 5 days for comparison.
            counter.setOriginalQuoteValidUntil(
                    LocalDate.now(clock).plusDays(Statuses.COUNTER_OFFER_COMPARISON_DAYS));
            persistence.run(Insert.into(CounterOffers_.class).entry(counter));
        }

        // Accept with conditions also creates the inspection order the condition names.
        if (Statuses.DECISION_ACCEPT_WITH_CONDITIONS.equals(outcome)
                && submission.getDwellingCoverage() != null) {
            inspections.orderForSubmission(submission, conditionDueInDays);
        }

        referral.setStatus(Statuses.REFERRAL_DECIDED);
        referral.setDecisionId(decision.getId());
        referrals.markSlaOutcomeOnDecision(referral);
        persistence.run(Update.entity(Referrals_.class).entry(referral));

        submission.setStatus(switch (outcome) {
            case Statuses.DECISION_ACCEPT, Statuses.DECISION_ACCEPT_WITH_CONDITIONS,
                 Statuses.DECISION_COUNTER_OFFER -> Statuses.SUBMISSION_BINDABLE;
            case Statuses.DECISION_DECLINE -> Statuses.SUBMISSION_DECLINED;
            default -> submission.getStatus();
        });
        if (Statuses.DECISION_DECLINE.equals(outcome)) {
            submission.setDeclineReason(reasonCode);
        }
        persistence.run(Update.entity(Submissions_.class).entry(submission));

        return load(decision.getId());
    }

    public UnderwriterDecisions load(String decisionId) {
        return persistence.run(Select.from(UnderwriterDecisions_.class)
                .where(d -> d.ID().eq(decisionId))
                .columns(d -> d._all(), d -> d.conditions().expand()))
                .first(UnderwriterDecisions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Decision not found: " + decisionId));
    }

    public CounterOffers counterOfferFor(String decisionId) {
        return persistence.run(Select.from(CounterOffers_.class)
                .where(c -> c.decision_ID().eq(decisionId)))
                .first(CounterOffers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No counter-offer on decision: " + decisionId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
