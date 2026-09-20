package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.AppetiteEntries;
import cds.gen.insurance.AppetiteEvaluations;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/appetite/define-underwriting-appetite.feature.md
 * "Appetite change does not disturb in-force policies", "Appetite entry drafted
 * without an approver", and "Conflicting appetite entries for the same class"
 * are [proposed] and intentionally absent.
 */
@DisplayName("Define Underwriting Appetite — specs/underwriting/appetite/define-underwriting-appetite.feature.md")
class DefineUnderwritingAppetiteTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Appetite entry published with an effective date @v1")
    void appetiteEntryPublishedWithAnEffectiveDate() {
        // A draft appetite entry for frame dwellings in Colorado
        AppetiteEntries draft = insertAppetiteEntry("frame dwelling over $1,500,000",
                Statuses.APPETITE_DECLINE, Statuses.APPETITE_DRAFT, "CO", null);

        LocalDate october1 = LocalDate.of(2026, 10, 1);
        AppetiteEntries published =
                db(() -> appetite.publish(draft.getId(), "Priya Raman", october1));

        assertThat(published.getStatus()).isEqualTo(Statuses.APPETITE_PUBLISHED);
        assertThat(published.getEffectiveDate()).isEqualTo(october1);
        assertThat(published.getPublishedBy()).isEqualTo("Priya Raman");

        // Submissions quoted before that date retain the prior appetite
        Submissions earlier = newSubmission(s -> {
        });
        AppetiteEvaluations before = db(() -> {
            Submissions s = eligibility.load(earlier.getId());
            s.setState("CO");
            s.setQuotedOn(october1.minusDays(1));
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Submissions_.class).entry(s));
            return appetite.evaluate(earlier.getId(), "frame dwelling over $1,500,000");
        });
        assertThat(before.getOutcome()).isEqualTo(Statuses.APPETITE_IN);

        // Submissions on or after October 1 are screened against the new entry
        Submissions later = newSubmission(s -> {
        });
        AppetiteEvaluations after = db(() -> {
            Submissions s = eligibility.load(later.getId());
            s.setState("CO");
            s.setQuotedOn(october1);
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Submissions_.class).entry(s));
            return appetite.evaluate(later.getId(), "frame dwelling over $1,500,000");
        });
        assertThat(after.getOutcome()).isEqualTo(Statuses.APPETITE_DECLINE);
    }

    @Test
    @DisplayName("Risk outside appetite is declined at screening @v1")
    void riskOutsideAppetiteIsDeclinedAtScreening() {
        // A published appetite excluding dwellings with fuel oil tanks below grade
        insertAppetiteEntry("fuel oil tank below grade", Statuses.APPETITE_DECLINE,
                Statuses.APPETITE_PUBLISHED, null, BASE_DATE.minusMonths(1));

        Submissions submission = newHomeSubmission();

        // A submission discloses a buried oil tank
        AppetiteEvaluations evaluation =
                db(() -> appetite.evaluate(submission.getId(), "fuel oil tank below grade"));

        // The submission is declined with reason "outside published appetite"
        assertThat(evaluation.getOutcome()).isEqualTo(Statuses.APPETITE_DECLINE);
        assertThat(evaluation.getReason()).isEqualTo(Statuses.DECLINE_OUTSIDE_APPETITE);
        Submissions declined = db(() -> eligibility.load(submission.getId()));
        assertThat(declined.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);
        assertThat(declined.getDeclineReason()).isEqualTo(Statuses.DECLINE_OUTSIDE_APPETITE);
    }

    @Test
    @DisplayName("In-appetite risk with an out-of-appetite feature @v1")
    void inAppetiteRiskWithAnOutOfAppetiteFeature() {
        // A wood-burning stove as the sole heat source refers rather than declines
        insertAppetiteEntry("wood-burning stove as sole heat source", Statuses.APPETITE_REFER,
                Statuses.APPETITE_PUBLISHED, null, BASE_DATE.minusMonths(1));

        Submissions submission = newHomeSubmission();
        AppetiteEvaluations evaluation = db(() ->
                appetite.evaluate(submission.getId(), "wood-burning stove as sole heat source"));

        // The submission is referred rather than declined
        assertThat(evaluation.getOutcome()).isEqualTo(Statuses.APPETITE_REFER);
        assertThat(db(() -> eligibility.load(submission.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_REFERRED);

        // The specific out-of-appetite feature is named on the referral
        assertThat(evaluation.getFeatureNamed())
                .isEqualTo("wood-burning stove as sole heat source");
    }
}
