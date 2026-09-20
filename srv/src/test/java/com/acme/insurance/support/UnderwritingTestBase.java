package com.acme.insurance.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.underwriting.AppetiteManager;
import com.acme.insurance.underwriting.AuthorityEscalationManager;
import com.acme.insurance.underwriting.CatastropheAggregationManager;
import com.acme.insurance.underwriting.EligibilityManager;
import com.acme.insurance.underwriting.InspectionManager;
import com.acme.insurance.underwriting.InspectionReviewManager;
import com.acme.insurance.underwriting.InsuranceScoreManager;
import com.acme.insurance.underwriting.LossControlSurveyManager;
import com.acme.insurance.underwriting.LossHistoryManager;
import com.acme.insurance.underwriting.MoratoriumManager;
import com.acme.insurance.underwriting.PriorCoverageManager;
import com.acme.insurance.underwriting.RecommendationManager;
import com.acme.insurance.underwriting.ReferralManager;
import com.acme.insurance.underwriting.TerritoryManager;
import com.acme.insurance.underwriting.UnderwriterDecisionManager;
import com.sap.cds.ql.Insert;

import cds.gen.insurance.AgencyTerritoryQuotas;
import cds.gen.insurance.AgencyTerritoryQuotas_;
import cds.gen.insurance.AppetiteEntries;
import cds.gen.insurance.AppetiteEntries_;
import cds.gen.insurance.CatastropheZones;
import cds.gen.insurance.CatastropheZones_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.TerritoryRestrictions;
import cds.gen.insurance.TerritoryRestrictions_;

/**
 * Fixtures for the underwriting scenario tests: submissions, catastrophe zones,
 * territory restrictions, agency quotas, and appetite entries.
 *
 * Specs: specs/underwriting/**.feature.md
 */
public abstract class UnderwritingTestBase extends ScenarioTestBase {

    @Autowired
    protected EligibilityManager eligibility;
    @Autowired
    protected LossHistoryManager lossHistory;
    @Autowired
    protected PriorCoverageManager priorCoverage;
    @Autowired
    protected InsuranceScoreManager insuranceScores;
    @Autowired
    protected ReferralManager referrals;
    @Autowired
    protected UnderwriterDecisionManager decisions;
    @Autowired
    protected AuthorityEscalationManager escalations;
    @Autowired
    protected AppetiteManager appetite;
    @Autowired
    protected TerritoryManager territories;
    @Autowired
    protected MoratoriumManager moratoria;
    @Autowired
    protected CatastropheAggregationManager aggregation;
    @Autowired
    protected InspectionManager inspections;
    @Autowired
    protected LossControlSurveyManager surveys;
    @Autowired
    protected InspectionReviewManager reviews;
    @Autowired
    protected RecommendationManager recommendations;
    @Autowired
    protected com.acme.insurance.policy.RenewalManager renewals;

    /** A homeowners submission in Texas, in an open territory. */
    protected Submissions newHomeSubmission() {
        return newSubmission(s -> {
        });
    }

    protected Submissions newSubmission(Consumer<Submissions> customize) {
        Submissions submission = db(() -> eligibility.submitApplication(
                Statuses.LOB_HOME, "TX", "308", "75001", "12 Oak Street, Dallas TX",
                "Jordan Rivera", "jordan.rivera@example.com", LocalDate.of(1985, 4, 12),
                "AG-100", new BigDecimal("480000.00")));
        if (customize != null) {
            customize.accept(submission);
        }
        return submission;
    }

    protected CatastropheZones insertZone(String zoneCode, String peril, BigDecimal capacity,
            BigDecimal aggregateTiv) {
        CatastropheZones zone = CatastropheZones.create();
        zone.setId(UUID.randomUUID().toString());
        zone.setZoneCode(zoneCode);
        zone.setPeril(peril);
        zone.setCapacityAmount(capacity);
        zone.setAggregateTiv(aggregateTiv);
        zone.setSoftStopPct(new BigDecimal("90.00"));
        zone.setHardStopPct(new BigDecimal("98.00"));
        zone.setReopenReviewDate(BASE_DATE.plusMonths(3));
        dbRun(() -> persistence.run(Insert.into(CatastropheZones_.class).entry(zone)));
        return zone;
    }

    protected TerritoryRestrictions closeTerritory(String territoryCode, String lineOfBusiness,
            String reason, LocalDate effectiveDate) {
        TerritoryRestrictions restriction = TerritoryRestrictions.create();
        restriction.setId(UUID.randomUUID().toString());
        restriction.setTerritoryCode(territoryCode);
        restriction.setLineOfBusiness(lineOfBusiness);
        restriction.setStatus(Statuses.TERRITORY_CLOSED);
        restriction.setClosureReason(reason);
        restriction.setEffectiveDate(effectiveDate);
        dbRun(() -> persistence.run(Insert.into(TerritoryRestrictions_.class).entry(restriction)));
        return restriction;
    }

    protected AgencyTerritoryQuotas insertQuota(String agencyId, String territoryCode,
            int monthlyQuota, int usedCount, String periodMonth) {
        AgencyTerritoryQuotas quota = AgencyTerritoryQuotas.create();
        quota.setId(UUID.randomUUID().toString());
        quota.setAgencyId(agencyId);
        quota.setTerritoryCode(territoryCode);
        quota.setMonthlyQuota(monthlyQuota);
        quota.setUsedCount(usedCount);
        quota.setPeriodMonth(periodMonth);
        quota.setPrincipalEmail("principal@agency.example");
        dbRun(() -> persistence.run(Insert.into(AgencyTerritoryQuotas_.class).entry(quota)));
        return quota;
    }

    protected AppetiteEntries insertAppetiteEntry(String featureName, String disposition,
            String status, String state, LocalDate effectiveDate) {
        AppetiteEntries entry = AppetiteEntries.create();
        entry.setId(UUID.randomUUID().toString());
        entry.setName(featureName);
        entry.setFeatureName(featureName);
        entry.setDisposition(disposition);
        entry.setStatus(status);
        entry.setState(state);
        entry.setLineOfBusiness(Statuses.LOB_HOME);
        entry.setEffectiveDate(effectiveDate);
        dbRun(() -> persistence.run(Insert.into(AppetiteEntries_.class).entry(entry)));
        return entry;
    }

    /** All rules passing — the clean-applicant baseline. */
    protected java.util.Map<String, Boolean> allRulesPass() {
        return EligibilityManager.ruleOutcomes(true, true, true, true, true);
    }

    protected List<String> emailSubjects() {
        return emails.sent().stream().map(m -> m.subject()).toList();
    }
}
