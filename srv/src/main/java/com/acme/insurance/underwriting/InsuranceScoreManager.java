package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

import cds.gen.insurance.AdverseActionNotices;
import cds.gen.insurance.AdverseActionNotices_;
import cds.gen.insurance.AdverseActionReasons;
import cds.gen.insurance.InsuranceScores;
import cds.gen.insurance.InsuranceScores_;
import cds.gen.insurance.ScoreTiers;
import cds.gen.insurance.ScoreTiers_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * Orders the credit-based insurance score, maps it to a rating tier, and issues
 * the adverse action notice an unfavorable tier requires.
 *
 * Spec: specs/underwriting/eligibility/verify-insurance-score.feature.md
 * The tier table is CSV seed data in db/data/insurance-ScoreTiers.csv.
 */
@Component
public class InsuranceScoreManager {

    /** Reason codes the vendor returns with an unfavorable score. */
    private static final List<String> SCORE_REASON_CODES =
            List.of("A12", "B04", "C21", "D07", "E15");

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public InsuranceScoreManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Score maps the applicant to a rating tier" — a 762 maps to tier B with a
     * 0.94 tier factor, and the model version and order date are stored.
     *
     * "No-hit applicant is placed in the neutral tier" — a thin file gets the
     * statutorily neutral tier and is not declined for lack of a score.
     */
    public InsuranceScores order(String submissionId, Integer score, Boolean noHit,
            String modelVersion, String reportingAgency) {
        loadSubmission(submissionId);
        boolean thinFile = Boolean.TRUE.equals(noHit) || score == null;

        InsuranceScores stored = InsuranceScores.create();
        stored.setId(UUID.randomUUID().toString());
        stored.setSubmissionId(submissionId);
        stored.setNoHit(thinFile);
        stored.setModelVersion(modelVersion);
        stored.setReportingAgency(reportingAgency);
        stored.setOrderedOn(LocalDate.now(clock));

        if (thinFile) {
            ScoreTiers neutral = neutralTier();
            stored.setScore(null);
            stored.setTier(neutral.getTier());
            stored.setTierFactor(neutral.getTierFactor());
        } else {
            ScoreTiers tier = tierFor(score);
            stored.setScore(score);
            stored.setTier(tier.getTier());
            stored.setTierFactor(tier.getTierFactor());
        }

        persistence.run(Insert.into(InsuranceScores_.class).entry(stored));
        return load(stored.getId());
    }

    /**
     * "Adverse action notice on an unfavorable score" — an applicant rated in
     * tier E because of the score receives a notice naming the consumer
     * reporting agency, mailed within 30 days, listing the top four reason codes.
     */
    public AdverseActionNotices issueAdverseActionNotice(String submissionId) {
        Submissions submission = loadSubmission(submissionId);
        InsuranceScores score = persistence.run(Select.from(InsuranceScores_.class)
                .where(s -> s.submission_ID().eq(submissionId)))
                .first(InsuranceScores.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No insurance score on submission: " + submissionId));

        LocalDate today = LocalDate.now(clock);

        List<AdverseActionReasons> reasons = new ArrayList<>();
        for (int rank = 1; rank <= Statuses.ADVERSE_ACTION_REASON_COUNT; rank++) {
            AdverseActionReasons reason = AdverseActionReasons.create();
            reason.setId(UUID.randomUUID().toString());
            reason.setRank(rank);
            reason.setCode(SCORE_REASON_CODES.get(rank - 1));
            reason.setNarrative("Score factor " + SCORE_REASON_CODES.get(rank - 1));
            reasons.add(reason);
        }

        AdverseActionNotices notice = AdverseActionNotices.create();
        notice.setId(UUID.randomUUID().toString());
        notice.setSubmissionId(submissionId);
        notice.setReportingAgency(score.getReportingAgency());
        notice.setMailByDate(today.plusDays(Statuses.ADVERSE_ACTION_NOTICE_DAYS));
        notice.setMailedOn(today);
        notice.setReasonCodes(reasons);
        persistence.run(Insert.into(AdverseActionNotices_.class).entry(notice));

        score.setAdverseActionId(notice.getId());
        persistence.run(Update.entity(InsuranceScores_.class).entry(score));

        email.send(new EmailMessage(submission.getApplicantEmail(),
                "Notice of adverse action",
                "Your policy was rated using a credit-based insurance score obtained from "
                        + score.getReportingAgency() + "."));

        return loadNotice(notice.getId());
    }

    private ScoreTiers tierFor(int score) {
        return persistence.run(Select.from(ScoreTiers_.class)
                .where(t -> t.minScore().le(score).and(t.maxScore().ge(score))))
                .first(ScoreTiers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No tier for insurance score " + score));
    }

    private ScoreTiers neutralTier() {
        return persistence.run(Select.from(ScoreTiers_.class)
                .where(t -> t.neutral().eq(true)))
                .first(ScoreTiers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No statutorily neutral tier configured"));
    }

    public InsuranceScores load(String scoreId) {
        return persistence.run(Select.from(InsuranceScores_.class)
                .where(s -> s.ID().eq(scoreId)))
                .first(InsuranceScores.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Insurance score not found: " + scoreId));
    }

    public AdverseActionNotices loadNotice(String noticeId) {
        return persistence.run(Select.from(AdverseActionNotices_.class)
                .where(n -> n.ID().eq(noticeId))
                .columns(n -> n._all(), n -> n.reasonCodes().expand()))
                .first(AdverseActionNotices.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Adverse action notice not found: " + noticeId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
