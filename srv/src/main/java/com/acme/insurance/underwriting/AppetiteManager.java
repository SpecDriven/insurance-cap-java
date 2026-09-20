package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

import cds.gen.insurance.AppetiteEntries;
import cds.gen.insurance.AppetiteEntries_;
import cds.gen.insurance.AppetiteEvaluations;
import cds.gen.insurance.AppetiteEvaluations_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * The published statement of what the company will write, and screening
 * submissions against it.
 *
 * Spec: specs/underwriting/appetite/define-underwriting-appetite.feature.md
 */
@Component
public class AppetiteManager {

    private final PersistenceService persistence;
    private final Clock clock;

    public AppetiteManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Appetite entry published with an effective date" — the chief underwriter
     * publishes the entry effective October 1; submissions on or after that date
     * are screened against it, and submissions quoted before it retain the prior
     * appetite.
     */
    public AppetiteEntries publish(String entryId, String publishedBy, LocalDate effectiveDate) {
        AppetiteEntries entry = load(entryId);
        entry.setStatus(Statuses.APPETITE_PUBLISHED);
        entry.setPublishedBy(publishedBy);
        entry.setPublishedAt(LocalDateTime.now(clock).atZone(clock.getZone()).toInstant());
        entry.setEffectiveDate(effectiveDate);
        persistence.run(Update.entity(AppetiteEntries_.class).entry(entry));
        return load(entryId);
    }

    /**
     * "Risk outside appetite is declined at screening" — a submission disclosing
     * an excluded feature (a buried oil tank) is declined with reason "outside
     * published appetite".
     *
     * "In-appetite risk with an out-of-appetite feature" — an otherwise
     * in-appetite dwelling with a refer-disposition feature is referred rather
     * than declined, and the specific feature is named on the referral.
     */
    public AppetiteEvaluations evaluate(String submissionId, String featureName) {
        Submissions submission = loadSubmission(submissionId);
        LocalDate screeningDate = submission.getQuotedOn() != null
                ? submission.getQuotedOn() : LocalDate.now(clock);

        AppetiteEntries matched = matchFeature(submission, featureName, screeningDate);

        AppetiteEvaluations evaluation = AppetiteEvaluations.create();
        evaluation.setId(UUID.randomUUID().toString());
        evaluation.setSubmissionId(submissionId);
        evaluation.setEvaluatedOn(screeningDate);

        if (matched == null) {
            evaluation.setOutcome(Statuses.APPETITE_IN);
        } else {
            evaluation.setAppetiteEntryId(matched.getId());
            if (Statuses.APPETITE_REFER.equals(matched.getDisposition())) {
                evaluation.setOutcome(Statuses.APPETITE_REFER);
                evaluation.setFeatureNamed(matched.getFeatureName());
                submission.setStatus(Statuses.SUBMISSION_REFERRED);
                persistence.run(Update.entity(Submissions_.class).entry(submission));
            } else {
                evaluation.setOutcome(Statuses.APPETITE_DECLINE);
                evaluation.setReason(Statuses.DECLINE_OUTSIDE_APPETITE);
                evaluation.setFeatureNamed(matched.getFeatureName());
                submission.setStatus(Statuses.SUBMISSION_DECLINED);
                submission.setDeclineReason(Statuses.DECLINE_OUTSIDE_APPETITE);
                persistence.run(Update.entity(Submissions_.class).entry(submission));
            }
        }

        persistence.run(Insert.into(AppetiteEvaluations_.class).entry(evaluation));
        return loadEvaluation(evaluation.getId());
    }

    /**
     * The published entry governing this feature on the screening date. Entries
     * effective after the screening date do not apply — a submission quoted
     * before the effective date retains the prior appetite.
     */
    private AppetiteEntries matchFeature(Submissions submission, String featureName,
            LocalDate screeningDate) {
        if (featureName == null || featureName.isBlank()) {
            return null;
        }
        List<AppetiteEntries> published = persistence.run(Select.from(AppetiteEntries_.class)
                .where(e -> e.status().eq(Statuses.APPETITE_PUBLISHED)))
                .listOf(AppetiteEntries.class);

        return published.stream()
                .filter(e -> featureName.equalsIgnoreCase(e.getFeatureName()))
                .filter(e -> e.getEffectiveDate() == null
                        || !screeningDate.isBefore(e.getEffectiveDate()))
                .filter(e -> e.getState() == null
                        || e.getState().equalsIgnoreCase(submission.getState()))
                .findFirst()
                .orElse(null);
    }

    public AppetiteEntries load(String entryId) {
        return persistence.run(Select.from(AppetiteEntries_.class)
                .where(e -> e.ID().eq(entryId)))
                .first(AppetiteEntries.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Appetite entry not found: " + entryId));
    }

    public AppetiteEvaluations loadEvaluation(String evaluationId) {
        return persistence.run(Select.from(AppetiteEvaluations_.class)
                .where(e -> e.ID().eq(evaluationId)))
                .first(AppetiteEvaluations.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Appetite evaluation not found: " + evaluationId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
