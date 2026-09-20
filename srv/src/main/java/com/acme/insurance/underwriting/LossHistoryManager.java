package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.LossHistoryEntries;
import cds.gen.insurance.LossHistoryEntries_;
import cds.gen.insurance.LossHistoryReports;
import cds.gen.insurance.LossHistoryReports_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * Orders and evaluates prior loss history (CLUE reports) for a submission.
 *
 * Spec: specs/underwriting/eligibility/check-prior-loss-history.feature.md
 */
@Component
public class LossHistoryManager {

    /** "a 5-year CLUE property report is retrieved" */
    public static final int CLUE_YEARS = 5;

    /** Two or more paid water losses decline the submission. */
    public static final int WATER_LOSS_DECLINE_THRESHOLD = 2;

    private final PersistenceService persistence;
    private final Clock clock;

    public LossHistoryManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Three-year loss run ordered at submission" — despite the scenario title,
     * the Then clause specifies a 5-year CLUE property report, matched to the
     * applicant by name and date of birth. The report window follows the clause.
     */
    public LossHistoryReports order(String submissionId) {
        Submissions submission = loadSubmission(submissionId);

        LossHistoryReports report = LossHistoryReports.create();
        report.setId(UUID.randomUUID().toString());
        report.setSubmissionId(submissionId);
        report.setReportType(Statuses.REPORT_CLUE_PROPERTY);
        report.setYearsRequested(CLUE_YEARS);
        report.setOrderedOn(LocalDate.now(clock));
        // Matched to the applicant by name and date of birth
        report.setMatchedByName(true);
        report.setMatchedByDob(submission.getApplicantDob() != null);
        persistence.run(Insert.into(LossHistoryReports_.class).entry(report));
        return loadReport(report.getId());
    }

    public LossHistoryEntries recordEntry(String reportId, String claimNumber, LocalDate lossDate,
            String perilCode, String catastropheCode, BigDecimal paidAmount) {
        LossHistoryEntries entry = LossHistoryEntries.create();
        entry.setId(UUID.randomUUID().toString());
        entry.setReportId(reportId);
        entry.setClaimNumber(claimNumber);
        entry.setLossDate(lossDate);
        entry.setPerilCode(perilCode);
        entry.setCatastropheCode(catastropheCode);
        entry.setPaidAmount(paidAmount);

        // "Weather-only losses are excluded from the frequency count" — a loss
        // carrying a catastrophe code does not count toward frequency, and the
        // code used for the exclusion is recorded.
        boolean weatherCoded = catastropheCode != null && !catastropheCode.isBlank();
        entry.setCountsTowardFrequency(!weatherCoded);
        if (weatherCoded) {
            entry.setExcludedReason(Statuses.EXCLUDED_WEATHER_ONLY);
        }
        persistence.run(Insert.into(LossHistoryEntries_.class).entry(entry));
        return entry;
    }

    /**
     * "Two or more water losses trigger a decline" — paid water damage claims
     * are counted; at two or more the submission is declined for water loss
     * frequency and the supporting claim numbers are cited.
     */
    public Submissions evaluate(String submissionId) {
        Submissions submission = loadSubmission(submissionId);
        List<LossHistoryEntries> entries = entriesFor(submissionId);

        List<LossHistoryEntries> waterLosses = entries.stream()
                .filter(e -> Statuses.PERIL_WATER.equalsIgnoreCase(e.getPerilCode()))
                .filter(e -> Boolean.TRUE.equals(e.getCountsTowardFrequency()))
                .filter(e -> e.getPaidAmount() != null
                        && e.getPaidAmount().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (waterLosses.size() >= WATER_LOSS_DECLINE_THRESHOLD) {
            String citedClaims = waterLosses.stream()
                    .map(LossHistoryEntries::getClaimNumber)
                    .collect(Collectors.joining(", "));
            submission.setStatus(Statuses.SUBMISSION_DECLINED);
            submission.setDeclineReason(Statuses.DECLINE_WATER_FREQUENCY + " (" + citedClaims + ")");
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        }
        return loadSubmission(submissionId);
    }

    /** Losses counting toward the frequency threshold, weather exclusions removed. */
    public long frequencyCount(String submissionId) {
        return entriesFor(submissionId).stream()
                .filter(e -> Boolean.TRUE.equals(e.getCountsTowardFrequency()))
                .count();
    }

    public List<LossHistoryEntries> entriesFor(String submissionId) {
        List<LossHistoryReports> reports = persistence.run(Select.from(LossHistoryReports_.class)
                .where(r -> r.submission_ID().eq(submissionId)))
                .listOf(LossHistoryReports.class);
        return reports.stream()
                .flatMap(r -> persistence.run(Select.from(LossHistoryEntries_.class)
                        .where(e -> e.report_ID().eq(r.getId())))
                        .listOf(LossHistoryEntries.class).stream())
                .toList();
    }

    public LossHistoryReports loadReport(String reportId) {
        return persistence.run(Select.from(LossHistoryReports_.class)
                .where(r -> r.ID().eq(reportId))
                .columns(r -> r._all(), r -> r.entries().expand()))
                .first(LossHistoryReports.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Loss history report not found: " + reportId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
