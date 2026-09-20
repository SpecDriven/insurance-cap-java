package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.LossControlSurveys;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/underwriting/inspections/order-loss-control-survey.feature.md
 * "Insured refuses access to the surveyor" and "Survey scheduled outside the
 * catastrophe season" are [proposed] and intentionally absent.
 */
@DisplayName("Order Loss Control Survey — specs/underwriting/inspections/order-loss-control-survey.feature.md")
class OrderLossControlSurveyTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Survey ordered on a high-value coastal dwelling @v1")
    void surveyOrderedOnAHighValueCoastalDwelling() {
        // A $3,200,000 dwelling within 1,500 feet of open water
        Policies policy = insertHomePolicy(
                p -> p.setDwellingCoverage(new BigDecimal("3200000.00")));

        LossControlSurveys survey = db(() -> surveys.order(policy.getId(),
                new BigDecimal("3200000.00"), 1200, null, null));

        // An engineering loss control survey is ordered with a 45-day due date
        assertThat(survey.getStatus()).isEqualTo(Statuses.INSPECTION_ORDERED);
        assertThat(survey.getDueDate()).isEqualTo(BASE_DATE.plusDays(Statuses.SURVEY_DUE_DAYS));

        // The survey scope includes wind mitigation features and roof attachment
        assertThat(survey.getScopeWindMitigation()).isTrue();
        assertThat(survey.getScopeRoofAttachment()).isTrue();
    }

    @Test
    @DisplayName("Survey waived when a recent report exists @v1")
    void surveyWaivedWhenARecentReportExists() {
        Policies policy = insertHomePolicy(
                p -> p.setDwellingCoverage(new BigDecimal("3200000.00")));

        // An engineering survey completed 14 months ago with no open recommendations
        var priorReportDate = BASE_DATE.minusMonths(14);
        LossControlSurveys survey = db(() -> surveys.order(policy.getId(),
                new BigDecimal("3200000.00"), 1200, priorReportDate, 0));

        // The order is waived and the prior report is attached to the current term
        assertThat(survey.getWaived()).isTrue();
        assertThat(survey.getStatus()).isEqualTo(Statuses.INSPECTION_WAIVED);

        // The waiver decision is recorded with the prior report date
        assertThat(survey.getPriorReportDate()).isEqualTo(priorReportDate);
        assertThat(survey.getWaiverReason()).contains(priorReportDate.toString());
    }

    @Test
    @DisplayName("Survey uncovers an unprotected fire exposure @v1")
    void surveyUncoversAnUnprotectedFireExposure() {
        Policies policy = insertHomePolicy(
                p -> p.setDwellingCoverage(new BigDecimal("3200000.00")));
        LossControlSurveys ordered = db(() -> surveys.order(policy.getId(),
                new BigDecimal("3200000.00"), 1200, null, null));
        emails.reset();

        // A survey finding that the nearest fire hydrant is 1,800 feet from the dwelling
        LossControlSurveys completed = db(() ->
                surveys.recordFireProtectionFinding(ordered.getId(), 1800, 4, 9));

        // The risk is moved from protection class 4 to class 9 at the anniversary
        assertThat(completed.getPriorProtectionClass()).isEqualTo(4);
        assertThat(completed.getNewProtectionClass()).isEqualTo(9);
        assertThat(completed.getProtectionClassEffectiveOn())
                .isEqualTo(policy.getExpirationDate());

        // The premium change is disclosed with 45 days notice
        assertThat(completed.getNoticeDays()).isEqualTo(Statuses.COVERAGE_CHANGE_NOTICE_DAYS);
        assertThat(emailSubjects()).contains("Change to your protection class");
    }
}
