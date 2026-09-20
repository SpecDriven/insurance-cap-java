package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.AdverseActionNotices;
import cds.gen.insurance.AdverseActionReasons;
import cds.gen.insurance.InsuranceScores;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/eligibility/verify-insurance-score.feature.md
 * "Extraordinary life event triggers a rescore", "Score cannot be used in a
 * prohibited state", and "Score is refreshed at renewal but cannot worsen the
 * tier" are [proposed] and intentionally absent.
 */
@DisplayName("Verify Insurance Score — specs/underwriting/eligibility/verify-insurance-score.feature.md")
class VerifyInsuranceScoreTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Score maps the applicant to a rating tier @v1")
    void scoreMapsTheApplicantToARatingTier() {
        Submissions submission = newHomeSubmission();

        // An insurance score of 762 returned by the scoring vendor
        InsuranceScores score = db(() -> insuranceScores.order(submission.getId(), 762, false,
                "TrueRisk 4.2", "Acme Consumer Reporting"));

        // The applicant is placed in tier B with a 0.94 tier factor
        assertThat(score.getTier()).isEqualTo("B");
        assertThat(score.getTierFactor()).isEqualByComparingTo(new BigDecimal("0.940"));

        // The score model version and order date are stored on the submission
        assertThat(score.getModelVersion()).isEqualTo("TrueRisk 4.2");
        assertThat(score.getOrderedOn()).isEqualTo(BASE_DATE);
    }

    @Test
    @DisplayName("No-hit applicant is placed in the neutral tier @v1")
    void noHitApplicantIsPlacedInTheNeutralTier() {
        Submissions submission = newHomeSubmission();

        // A thin-file applicant for whom the vendor returns no score
        InsuranceScores score = db(() -> insuranceScores.order(submission.getId(), null, true,
                "TrueRisk 4.2", "Acme Consumer Reporting"));

        // The applicant is assigned the statutorily neutral tier
        assertThat(score.getNoHit()).isTrue();
        assertThat(score.getScore()).isNull();
        assertThat(score.getTier()).isEqualTo(Statuses.TIER_NEUTRAL);

        // The submission is not declined for lack of a score
        assertThat(db(() -> eligibility.load(submission.getId())).getStatus())
                .isNotEqualTo(Statuses.SUBMISSION_DECLINED);
    }

    @Test
    @DisplayName("Adverse action notice on an unfavorable score @v1")
    void adverseActionNoticeOnAnUnfavorableScore() {
        Submissions submission = newHomeSubmission();

        // An applicant rated in tier E because of the insurance score
        InsuranceScores score = db(() -> insuranceScores.order(submission.getId(), 540, false,
                "TrueRisk 4.2", "Acme Consumer Reporting"));
        assertThat(score.getTier()).isEqualTo("E");

        AdverseActionNotices notice =
                db(() -> insuranceScores.issueAdverseActionNotice(submission.getId()));

        // A notice naming the consumer reporting agency is mailed within 30 days
        assertThat(notice.getReportingAgency()).isEqualTo("Acme Consumer Reporting");
        assertThat(notice.getMailByDate())
                .isEqualTo(BASE_DATE.plusDays(Statuses.ADVERSE_ACTION_NOTICE_DAYS));
        assertThat(notice.getMailedOn()).isBeforeOrEqualTo(notice.getMailByDate());
        assertThat(emailSubjects()).contains("Notice of adverse action");

        // The top four score reason codes are listed in the notice
        assertThat(notice.getReasonCodes()).hasSize(Statuses.ADVERSE_ACTION_REASON_COUNT);
        assertThat(notice.getReasonCodes())
                .extracting(AdverseActionReasons::getRank)
                .containsExactlyInAnyOrder(1, 2, 3, 4);
    }
}
