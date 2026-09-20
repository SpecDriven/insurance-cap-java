package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.LossHistoryReports;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/eligibility/check-prior-loss-history.feature.md
 * "Applicant disputes a loss that is not theirs", "Closed-without-payment
 * claims are counted separately", and "Losses from an undisclosed household
 * member" are [proposed] and intentionally absent.
 */
@DisplayName("Check Prior Loss History — specs/underwriting/eligibility/check-prior-loss-history.feature.md")
class CheckPriorLossHistoryTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Three-year loss run ordered at submission @v1")
    void threeYearLossRunOrderedAtSubmission() {
        Submissions submission = newHomeSubmission();

        LossHistoryReports report = db(() -> lossHistory.order(submission.getId()));

        // A 5-year CLUE property report is retrieved for the applicant and the address
        assertThat(report.getReportType()).isEqualTo(Statuses.REPORT_CLUE_PROPERTY);
        assertThat(report.getYearsRequested()).isEqualTo(LossHistoryManager.CLUE_YEARS);
        assertThat(report.getOrderedOn()).isEqualTo(BASE_DATE);

        // Every reported loss is matched to the applicant by name and date of birth
        assertThat(report.getMatchedByName()).isTrue();
        assertThat(report.getMatchedByDob()).isTrue();
    }

    @Test
    @DisplayName("Two or more water losses trigger a decline @v1")
    void twoOrMoreWaterLossesTriggerADecline() {
        Submissions submission = newHomeSubmission();
        LossHistoryReports report = db(() -> lossHistory.order(submission.getId()));

        // Water damage claims paid in 2022 and 2024
        db(() -> lossHistory.recordEntry(report.getId(), "CLM-2022-118",
                LocalDate.of(2022, 6, 3), Statuses.PERIL_WATER, null,
                new BigDecimal("8400.00")));
        db(() -> lossHistory.recordEntry(report.getId(), "CLM-2024-553",
                LocalDate.of(2024, 2, 19), Statuses.PERIL_WATER, null,
                new BigDecimal("12750.00")));

        Submissions evaluated = db(() -> lossHistory.evaluate(submission.getId()));

        // The submission is declined for water loss frequency
        assertThat(evaluated.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);
        assertThat(evaluated.getDeclineReason()).startsWith(Statuses.DECLINE_WATER_FREQUENCY);

        // The claim numbers supporting the decline are cited in the notice
        assertThat(evaluated.getDeclineReason())
                .contains("CLM-2022-118")
                .contains("CLM-2024-553");
    }

    @Test
    @DisplayName("Weather-only losses are excluded from the frequency count @v1")
    void weatherOnlyLossesAreExcludedFromTheFrequencyCount() {
        Submissions submission = newHomeSubmission();
        LossHistoryReports report = db(() -> lossHistory.order(submission.getId()));

        // Three losses, of which two are hail losses under a statewide catastrophe code
        db(() -> lossHistory.recordEntry(report.getId(), "CLM-A", LocalDate.of(2023, 5, 8),
                "Hail", "TX-CAT-2023-04", new BigDecimal("6100.00")));
        db(() -> lossHistory.recordEntry(report.getId(), "CLM-B", LocalDate.of(2024, 4, 22),
                "Hail", "TX-CAT-2024-02", new BigDecimal("9300.00")));
        db(() -> lossHistory.recordEntry(report.getId(), "CLM-C", LocalDate.of(2024, 9, 14),
                "Theft", null, new BigDecimal("2200.00")));

        // Only the single non-weather loss counts toward the frequency threshold
        assertThat(db(() -> lossHistory.frequencyCount(submission.getId()))).isEqualTo(1);

        // The catastrophe codes used for the exclusion are recorded
        var excluded = db(() -> lossHistory.entriesFor(submission.getId())).stream()
                .filter(e -> !e.getCountsTowardFrequency())
                .toList();
        assertThat(excluded).hasSize(2);
        assertThat(excluded).allSatisfy(e -> {
            assertThat(e.getExcludedReason()).isEqualTo(Statuses.EXCLUDED_WEATHER_ONLY);
            assertThat(e.getCatastropheCode()).isNotBlank();
        });
    }
}
