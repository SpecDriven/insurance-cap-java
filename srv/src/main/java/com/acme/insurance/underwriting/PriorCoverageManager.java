package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.PriorCoverageHistories;
import cds.gen.insurance.PriorCoverageHistories_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * Evaluates the applicant's prior coverage: lapses, and cancellations that are
 * disqualifying versus benign.
 *
 * Spec: specs/underwriting/eligibility/detect-prior-cancellation.feature.md
 */
@Component
public class PriorCoverageManager {

    /** Two or more non-payment cancellations in 12 months forces payment in advance. */
    public static final int NON_PAYMENT_REFERRAL_THRESHOLD = 2;

    private final PersistenceService persistence;
    private final Clock clock;

    public PriorCoverageManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Gap in coverage longer than 30 days" — the continuous-coverage discount
     * is removed and the submission is rated at the no-prior-insurance tier.
     *
     * "Insured-initiated cancellation is not disqualifying" — classified as
     * insured-requested; eligibility is untouched.
     *
     * "Non-payment cancellation within the last year" — two non-payment
     * cancellations refer the submission, require payment in advance, and
     * suppress the installment plan option on the quote.
     *
     * "Prior cancellation for material misrepresentation" — declined, with the
     * prior carrier name and cancellation date recorded on the decline.
     */
    public PriorCoverageHistories evaluate(String submissionId, String priorCarrierName,
            LocalDate coverageEndedOn, LocalDate requestedEffectiveDate, String classification,
            Integer nonPaymentCount12m, LocalDate cancellationDate) {
        Submissions submission = loadSubmission(submissionId);

        PriorCoverageHistories history = PriorCoverageHistories.create();
        history.setId(UUID.randomUUID().toString());
        history.setSubmissionId(submissionId);
        history.setPriorCarrierName(priorCarrierName);
        history.setCoverageEndedOn(coverageEndedOn);
        history.setRequestedEffectiveDate(requestedEffectiveDate);
        history.setClassification(classification);
        history.setNonPaymentCount12m(nonPaymentCount12m == null ? 0 : nonPaymentCount12m);
        history.setCancellationDate(cancellationDate);

        int lapseDays = 0;
        if (coverageEndedOn != null && requestedEffectiveDate != null) {
            lapseDays = (int) ChronoUnit.DAYS.between(coverageEndedOn, requestedEffectiveDate);
        }
        history.setLapseDays(lapseDays);

        boolean lapsed = lapseDays > Statuses.LAPSE_THRESHOLD_DAYS;
        history.setContinuousCoverageDiscount(!lapsed);
        if (lapsed) {
            history.setRatingTier(Statuses.TIER_NO_PRIOR_INSURANCE);
        }

        boolean repeatNonPayment = Statuses.PRIOR_NON_PAYMENT.equals(classification)
                && history.getNonPaymentCount12m() >= NON_PAYMENT_REFERRAL_THRESHOLD;
        history.setPaymentInAdvanceRequired(repeatNonPayment);
        history.setInstallmentsSuppressed(repeatNonPayment);

        persistence.run(Insert.into(PriorCoverageHistories_.class).entry(history));

        if (Statuses.PRIOR_MISREPRESENTATION.equals(classification)) {
            submission.setStatus(Statuses.SUBMISSION_DECLINED);
            submission.setDeclineReason("prior cancellation for material misrepresentation — "
                    + priorCarrierName + ", " + cancellationDate);
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        } else if (repeatNonPayment) {
            submission.setStatus(Statuses.SUBMISSION_REFERRED);
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        }
        // Insured-requested cancellations do not affect eligibility.

        return load(history.getId());
    }

    public PriorCoverageHistories load(String historyId) {
        return persistence.run(Select.from(PriorCoverageHistories_.class)
                .where(h -> h.ID().eq(historyId)))
                .first(PriorCoverageHistories.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Prior coverage history not found: " + historyId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
