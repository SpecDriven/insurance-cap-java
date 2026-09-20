package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.EligibilityScreenings;
import cds.gen.insurance.EligibilityScreenings_;
import cds.gen.insurance.ScreeningRuleResults;
import cds.gen.insurance.ScreeningRules;
import cds.gen.insurance.ScreeningRules_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * Screens new business submissions against the company eligibility rules.
 *
 * Spec: specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
 * The rule table (outcome on failure, overridable) is CSV seed data in
 * db/data/insurance-ScreeningRules.csv, per ARCHITECTURE.md "Rate data".
 */
@Component
public class EligibilityManager {

    /** A screening result is valid for 60 days before it must be rerun. */
    public static final int SCREENING_VALID_DAYS = 60;

    private final PersistenceService persistence;
    private final Clock clock;

    public EligibilityManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    public Submissions submitApplication(String lineOfBusiness, String state, String territoryCode,
            String garagingZip, String riskAddress, String applicantName, String applicantEmail,
            LocalDate applicantDob, String agencyId, java.math.BigDecimal dwellingCoverage) {
        Submissions submission = Submissions.create();
        submission.setId(UUID.randomUUID().toString());
        submission.setSubmissionNumber("SUB-" + UUID.randomUUID().toString().substring(0, 8));
        submission.setLineOfBusiness(lineOfBusiness);
        submission.setState(state);
        submission.setTerritoryCode(territoryCode);
        submission.setGaragingZip(garagingZip);
        submission.setRiskAddress(riskAddress);
        submission.setApplicantName(applicantName);
        submission.setApplicantEmail(applicantEmail);
        submission.setApplicantDob(applicantDob);
        submission.setAgencyId(agencyId);
        submission.setDwellingCoverage(dwellingCoverage);
        submission.setStatus(Statuses.SUBMISSION_SCREENING);
        persistence.run(Insert.into(Submissions_.class).entry(submission));
        return load(submission.getId());
    }

    /**
     * Runs the screening rule set over a submission.
     *
     * "Clean applicant clears screening automatically" — all rules pass, the
     * submission is marked eligible and the result is stamped with the rule set
     * version. "Unlicensed driver on the application" — a non-overridable
     * decline rule fails, so the submission is declined. "Applicant fails two of
     * four screening checks" — only refer-rules fail, so the submission is
     * referred rather than auto-declined, and both failed rules are listed.
     *
     * "Screening result is valid for 60 days" — an existing result older than 60
     * days is discarded (superseded) and screening reruns.
     */
    public EligibilityScreenings screen(String submissionId, Map<String, Boolean> ruleOutcomes) {
        Submissions submission = load(submissionId);
        LocalDate today = LocalDate.now(clock);

        supersedePriorScreenings(submissionId, today);

        List<ScreeningRules> rules = persistence.run(Select.from(ScreeningRules_.class)
                .orderBy(r -> r.displayOrder().asc()))
                .listOf(ScreeningRules.class);

        List<ScreeningRuleResults> results = new ArrayList<>();
        boolean anyDecline = false;
        boolean anyRefer = false;

        for (ScreeningRules rule : rules) {
            Boolean passed = ruleOutcomes.get(rule.getRuleName());
            if (passed == null) {
                passed = Boolean.TRUE;
            }
            ScreeningRuleResults result = ScreeningRuleResults.create();
            result.setId(UUID.randomUUID().toString());
            result.setRuleName(rule.getRuleName());
            result.setPassed(passed);
            result.setOutcomeOnFailure(rule.getOutcomeOnFailure());
            result.setOverridable(rule.getOverridable());
            result.setEvaluatedValue(passed ? "pass" : "fail");
            results.add(result);

            if (!passed) {
                if (Statuses.OUTCOME_DECLINE.equals(rule.getOutcomeOnFailure())) {
                    anyDecline = true;
                } else {
                    anyRefer = true;
                }
            }
        }

        String outcome = anyDecline ? Statuses.SUBMISSION_DECLINED
                : anyRefer ? Statuses.SUBMISSION_REFERRED
                : Statuses.SUBMISSION_ELIGIBLE;

        EligibilityScreenings screening = EligibilityScreenings.create();
        screening.setId(UUID.randomUUID().toString());
        screening.setSubmissionId(submissionId);
        screening.setScreenedOn(today);
        screening.setRuleSetVersion(Statuses.SCREENING_RULE_SET_VERSION);
        screening.setOutcome(outcome);
        screening.setExpiresOn(today.plusDays(SCREENING_VALID_DAYS));
        screening.setSuperseded(false);
        screening.setResults(results);
        persistence.run(Insert.into(EligibilityScreenings_.class).entry(screening));

        submission.setStatus(outcome);
        if (anyDecline) {
            submission.setDeclineReason(declineReasonFor(results));
        }
        persistence.run(Update.entity(Submissions_.class).entry(submission));

        return loadScreening(screening.getId());
    }

    /** Failed rules, in rule-table order — listed on the referral. */
    public List<String> failedRuleNames(EligibilityScreenings screening) {
        return screening.getResults().stream()
                .filter(r -> !Boolean.TRUE.equals(r.getPassed()))
                .map(ScreeningRuleResults::getRuleName)
                .toList();
    }

    /**
     * Discards a screening result older than 60 days so it is rerun.
     * Spec: "Screening result is valid for 60 days".
     */
    private void supersedePriorScreenings(String submissionId, LocalDate today) {
        List<EligibilityScreenings> prior = persistence.run(Select.from(EligibilityScreenings_.class)
                .where(s -> s.submission_ID().eq(submissionId).and(s.superseded().eq(false))))
                .listOf(EligibilityScreenings.class);
        for (EligibilityScreenings screening : prior) {
            screening.setSuperseded(true);
            persistence.run(Update.entity(EligibilityScreenings_.class).entry(screening));
        }
    }

    private String declineReasonFor(List<ScreeningRuleResults> results) {
        boolean licenseFailed = results.stream()
                .anyMatch(r -> Statuses.RULE_VALID_LICENSE.equals(r.getRuleName())
                        && !Boolean.TRUE.equals(r.getPassed()));
        return licenseFailed ? Statuses.DECLINE_NO_LICENSE : Statuses.DECLINE_OUTSIDE_APPETITE;
    }

    public Submissions load(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }

    public EligibilityScreenings loadScreening(String screeningId) {
        return persistence.run(Select.from(EligibilityScreenings_.class)
                .where(s -> s.ID().eq(screeningId))
                .columns(s -> s._all(), s -> s.results().expand()))
                .first(EligibilityScreenings.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Screening not found: " + screeningId));
    }

    /** Rule-name keyed map in the order the rule table defines. */
    public static Map<String, Boolean> ruleOutcomes(boolean validLicense, boolean lossHistory,
            boolean insuranceScore, boolean priorCancellation, boolean residency) {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        outcomes.put(Statuses.RULE_VALID_LICENSE, validLicense);
        outcomes.put(Statuses.RULE_LOSS_HISTORY, lossHistory);
        outcomes.put(Statuses.RULE_INSURANCE_SCORE, insuranceScore);
        outcomes.put(Statuses.RULE_PRIOR_CANCELLATION, priorCancellation);
        outcomes.put(Statuses.RULE_RESIDENCY, residency);
        return outcomes;
    }
}
