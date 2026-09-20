package com.acme.insurance.handlers;

import java.util.List;

import org.springframework.stereotype.Component;

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
import com.sap.cds.Struct;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.underwritingservice.AddPolicyToZoneAggregateContext;
import cds.gen.underwritingservice.AggregateSnapshots;
import cds.gen.underwritingservice.AssignReferralContext;
import cds.gen.underwritingservice.AwaitAgentInformationContext;
import cds.gen.underwritingservice.CancelStormWatchContext;
import cds.gen.underwritingservice.EscalateAuthorityLimitContext;
import cds.gen.underwritingservice.EvaluateAppetiteContext;
import cds.gen.underwritingservice.EvaluateLossHistoryContext;
import cds.gen.underwritingservice.EvaluatePriorCoverageContext;
import cds.gen.underwritingservice.ExpireRecommendationCurePeriodsContext;
import cds.gen.underwritingservice.InspectionRecommendations;
import cds.gen.underwritingservice.IssueAdverseActionNoticeContext;
import cds.gen.underwritingservice.LiftDueMoratoriaContext;
import cds.gen.underwritingservice.Moratoria;
import cds.gen.underwritingservice.OpenMoratoriumContext;
import cds.gen.underwritingservice.OpenRecommendationContext;
import cds.gen.underwritingservice.OrderInsuranceScoreContext;
import cds.gen.underwritingservice.OrderLossControlSurveyContext;
import cds.gen.underwritingservice.OrderLossHistoryContext;
import cds.gen.underwritingservice.OrderPropertyInspectionContext;
import cds.gen.underwritingservice.PublishAppetiteEntryContext;
import cds.gen.underwritingservice.RecordFireProtectionFindingContext;
import cds.gen.underwritingservice.RecordLossHistoryEntryContext;
import cds.gen.underwritingservice.RecordNoAccessResultContext;
import cds.gen.underwritingservice.RecordUnderwriterDecisionContext;
import cds.gen.underwritingservice.ReferToUnderwriterContext;
import cds.gen.underwritingservice.Referrals;
import cds.gen.underwritingservice.ReopenTerritoryContext;
import cds.gen.underwritingservice.RequestCoverageIncreaseContext;
import cds.gen.underwritingservice.ResumeReferralClockContext;
import cds.gen.underwritingservice.ReviewInspectionFindingsContext;
import cds.gen.underwritingservice.RunSlaMonitorContext;
import cds.gen.underwritingservice.ScreenCatastropheCapacityContext;
import cds.gen.underwritingservice.ScreenEligibilityContext;
import cds.gen.underwritingservice.ScreenTerritoryContext;
import cds.gen.underwritingservice.SubmitApplicationContext;
import cds.gen.underwritingservice.SubmitRecommendationProofContext;
import cds.gen.underwritingservice.UnderwritingService_;

/**
 * OData actions for the underwriting domain.
 *
 * Specs: specs/underwriting/**.feature.md — only [published] scenarios are
 * exposed; [proposed] behavior is intentionally absent (ARCHITECTURE.md
 * "Spec traceability").
 */
@Component
@ServiceName(UnderwritingService_.CDS_NAME)
public class UnderwritingServiceHandler implements EventHandler {

    private final EligibilityManager eligibility;
    private final LossHistoryManager lossHistory;
    private final PriorCoverageManager priorCoverage;
    private final InsuranceScoreManager insuranceScores;
    private final ReferralManager referrals;
    private final UnderwriterDecisionManager decisions;
    private final AuthorityEscalationManager escalations;
    private final AppetiteManager appetite;
    private final TerritoryManager territories;
    private final MoratoriumManager moratoria;
    private final CatastropheAggregationManager aggregation;
    private final InspectionManager inspections;
    private final LossControlSurveyManager surveys;
    private final InspectionReviewManager reviews;
    private final RecommendationManager recommendations;

    public UnderwritingServiceHandler(EligibilityManager eligibility,
            LossHistoryManager lossHistory, PriorCoverageManager priorCoverage,
            InsuranceScoreManager insuranceScores, ReferralManager referrals,
            UnderwriterDecisionManager decisions, AuthorityEscalationManager escalations,
            AppetiteManager appetite, TerritoryManager territories, MoratoriumManager moratoria,
            CatastropheAggregationManager aggregation, InspectionManager inspections,
            LossControlSurveyManager surveys, InspectionReviewManager reviews,
            RecommendationManager recommendations) {
        this.eligibility = eligibility;
        this.lossHistory = lossHistory;
        this.priorCoverage = priorCoverage;
        this.insuranceScores = insuranceScores;
        this.referrals = referrals;
        this.decisions = decisions;
        this.escalations = escalations;
        this.appetite = appetite;
        this.territories = territories;
        this.moratoria = moratoria;
        this.aggregation = aggregation;
        this.inspections = inspections;
        this.surveys = surveys;
        this.reviews = reviews;
        this.recommendations = recommendations;
    }

    // ─── Eligibility ───

    @On(event = SubmitApplicationContext.CDS_NAME)
    public void onSubmitApplication(SubmitApplicationContext context) {
        var submission = eligibility.submitApplication(context.getLineOfBusiness(),
                context.getState(), context.getTerritoryCode(), context.getGaragingZip(),
                context.getRiskAddress(), context.getApplicantName(), context.getApplicantEmail(),
                context.getApplicantDob(), context.getAgencyId(), context.getDwellingCoverage());
        context.setResult(Struct.access(submission)
                .as(cds.gen.underwritingservice.Submissions.class));
        context.setCompleted();
    }

    @On(event = ScreenEligibilityContext.CDS_NAME)
    public void onScreenEligibility(ScreenEligibilityContext context) {
        var screening = eligibility.screen(context.getSubmissionId(),
                EligibilityManager.ruleOutcomes(
                        Boolean.TRUE.equals(context.getValidLicense()),
                        Boolean.TRUE.equals(context.getLossHistoryPasses()),
                        Boolean.TRUE.equals(context.getInsuranceScorePasses()),
                        Boolean.TRUE.equals(context.getPriorCancellationPasses()),
                        Boolean.TRUE.equals(context.getResidencyInState())));
        context.setResult(Struct.access(screening)
                .as(cds.gen.underwritingservice.EligibilityScreenings.class));
        context.setCompleted();
    }

    @On(event = OrderLossHistoryContext.CDS_NAME)
    public void onOrderLossHistory(OrderLossHistoryContext context) {
        var report = lossHistory.order(context.getSubmissionId());
        context.setResult(Struct.access(report)
                .as(cds.gen.underwritingservice.LossHistoryReports.class));
        context.setCompleted();
    }

    @On(event = RecordLossHistoryEntryContext.CDS_NAME)
    public void onRecordLossHistoryEntry(RecordLossHistoryEntryContext context) {
        var entry = lossHistory.recordEntry(context.getReportId(), context.getClaimNumber(),
                context.getLossDate(), context.getPerilCode(), context.getCatastropheCode(),
                context.getPaidAmount());
        context.setResult(Struct.access(entry)
                .as(cds.gen.underwritingservice.LossHistoryEntries.class));
        context.setCompleted();
    }

    @On(event = EvaluateLossHistoryContext.CDS_NAME)
    public void onEvaluateLossHistory(EvaluateLossHistoryContext context) {
        var submission = lossHistory.evaluate(context.getSubmissionId());
        context.setResult(Struct.access(submission)
                .as(cds.gen.underwritingservice.Submissions.class));
        context.setCompleted();
    }

    @On(event = EvaluatePriorCoverageContext.CDS_NAME)
    public void onEvaluatePriorCoverage(EvaluatePriorCoverageContext context) {
        var history = priorCoverage.evaluate(context.getSubmissionId(),
                context.getPriorCarrierName(), context.getCoverageEndedOn(),
                context.getRequestedEffectiveDate(), context.getClassification(),
                context.getNonPaymentCount12m(), context.getCancellationDate());
        context.setResult(Struct.access(history)
                .as(cds.gen.underwritingservice.PriorCoverageHistories.class));
        context.setCompleted();
    }

    @On(event = OrderInsuranceScoreContext.CDS_NAME)
    public void onOrderInsuranceScore(OrderInsuranceScoreContext context) {
        var score = insuranceScores.order(context.getSubmissionId(), context.getScore(),
                context.getNoHit(), context.getModelVersion(), context.getReportingAgency());
        context.setResult(Struct.access(score)
                .as(cds.gen.underwritingservice.InsuranceScores.class));
        context.setCompleted();
    }

    @On(event = IssueAdverseActionNoticeContext.CDS_NAME)
    public void onIssueAdverseActionNotice(IssueAdverseActionNoticeContext context) {
        var notice = insuranceScores.issueAdverseActionNotice(context.getSubmissionId());
        context.setResult(Struct.access(notice)
                .as(cds.gen.underwritingservice.AdverseActionNotices.class));
        context.setCompleted();
    }

    // ─── Referrals ───

    @On(event = ReferToUnderwriterContext.CDS_NAME)
    public void onReferToUnderwriter(ReferToUnderwriterContext context) {
        var referral = referrals.refer(context.getSubmissionId(), context.getReferralType(),
                context.getQueueName());
        context.setResult(Struct.access(referral).as(Referrals.class));
        context.setCompleted();
    }

    @On(event = AssignReferralContext.CDS_NAME)
    public void onAssignReferral(AssignReferralContext context) {
        var referral = referrals.assign(context.getReferralId(), context.getAssignedTo());
        context.setResult(Struct.access(referral).as(Referrals.class));
        context.setCompleted();
    }

    @On(event = AwaitAgentInformationContext.CDS_NAME)
    public void onAwaitAgentInformation(AwaitAgentInformationContext context) {
        var referral = referrals.awaitAgentInformation(context.getReferralId());
        context.setResult(Struct.access(referral).as(Referrals.class));
        context.setCompleted();
    }

    @On(event = ResumeReferralClockContext.CDS_NAME)
    public void onResumeReferralClock(ResumeReferralClockContext context) {
        var referral = referrals.resumeClock(context.getReferralId());
        context.setResult(Struct.access(referral).as(Referrals.class));
        context.setCompleted();
    }

    @On(event = RunSlaMonitorContext.CDS_NAME)
    public void onRunSlaMonitor(RunSlaMonitorContext context) {
        List<Referrals> touched = referrals.runSlaMonitor().stream()
                .map(r -> Struct.access(r).as(Referrals.class))
                .toList();
        context.setResult(touched);
        context.setCompleted();
    }

    @On(event = RecordUnderwriterDecisionContext.CDS_NAME)
    public void onRecordUnderwriterDecision(RecordUnderwriterDecisionContext context) {
        var decision = decisions.record(context.getReferralId(), context.getOutcome(),
                context.getUnderwriterName(), context.getReasonCode(), context.getNarrative(),
                context.getConditionDescription(), context.getConditionDueInDays(),
                context.getCounterOfferDescription(), context.getRevisedPremium());
        context.setResult(Struct.access(decision)
                .as(cds.gen.underwritingservice.UnderwriterDecisions.class));
        context.setCompleted();
    }

    @On(event = EscalateAuthorityLimitContext.CDS_NAME)
    public void onEscalateAuthorityLimit(EscalateAuthorityLimitContext context) {
        var escalation = escalations.escalate(context.getReferralId(), context.getEscalationType(),
                context.getRequestedAmount(), context.getRequestedCreditPct(),
                context.getUnderwriterRole(), context.getRecommendation(),
                context.getRecommendedOutcome(), context.getJustification());
        context.setResult(Struct.access(escalation)
                .as(cds.gen.underwritingservice.AuthorityEscalations.class));
        context.setCompleted();
    }

    // ─── Appetite, territory, moratorium, catastrophe aggregation ───

    @On(event = PublishAppetiteEntryContext.CDS_NAME)
    public void onPublishAppetiteEntry(PublishAppetiteEntryContext context) {
        var entry = appetite.publish(context.getEntryId(), context.getPublishedBy(),
                context.getEffectiveDate());
        context.setResult(Struct.access(entry)
                .as(cds.gen.underwritingservice.AppetiteEntries.class));
        context.setCompleted();
    }

    @On(event = EvaluateAppetiteContext.CDS_NAME)
    public void onEvaluateAppetite(EvaluateAppetiteContext context) {
        var evaluation = appetite.evaluate(context.getSubmissionId(), context.getFeatureName());
        context.setResult(Struct.access(evaluation)
                .as(cds.gen.underwritingservice.AppetiteEvaluations.class));
        context.setCompleted();
    }

    @On(event = ScreenTerritoryContext.CDS_NAME)
    public void onScreenTerritory(ScreenTerritoryContext context) {
        var submission = territories.screen(context.getSubmissionId());
        context.setResult(Struct.access(submission)
                .as(cds.gen.underwritingservice.Submissions.class));
        context.setCompleted();
    }

    @On(event = ReopenTerritoryContext.CDS_NAME)
    public void onReopenTerritory(ReopenTerritoryContext context) {
        var restriction = territories.reopen(context.getTerritoryCode(),
                context.getLineOfBusiness(), context.getReopenDate());
        context.setResult(Struct.access(restriction)
                .as(cds.gen.underwritingservice.TerritoryRestrictions.class));
        context.setCompleted();
    }

    @On(event = OpenMoratoriumContext.CDS_NAME)
    public void onOpenMoratorium(OpenMoratoriumContext context) {
        var moratorium = moratoria.open(context.getMoratoriumId(), context.getPeril(),
                context.getAreaType(), context.getAreaCodes());
        context.setResult(Struct.access(moratorium).as(Moratoria.class));
        context.setCompleted();
    }

    @On(event = CancelStormWatchContext.CDS_NAME)
    public void onCancelStormWatch(CancelStormWatchContext context) {
        var moratorium = moratoria.cancelStormWatch(context.getMoratoriumId());
        context.setResult(Struct.access(moratorium).as(Moratoria.class));
        context.setCompleted();
    }

    @On(event = LiftDueMoratoriaContext.CDS_NAME)
    public void onLiftDueMoratoria(LiftDueMoratoriaContext context) {
        List<Moratoria> lifted = moratoria.liftDue().stream()
                .map(m -> Struct.access(m).as(Moratoria.class))
                .toList();
        context.setResult(lifted);
        context.setCompleted();
    }

    @On(event = RequestCoverageIncreaseContext.CDS_NAME)
    public void onRequestCoverageIncrease(RequestCoverageIncreaseContext context) {
        var blocked = moratoria.requestCoverageIncrease(context.getPolicyId(),
                context.getRequestedLimit(), context.getZipCode());
        context.setResult(Struct.access(blocked)
                .as(cds.gen.underwritingservice.BlockedRequests.class));
        context.setCompleted();
    }

    @On(event = AddPolicyToZoneAggregateContext.CDS_NAME)
    public void onAddPolicyToZoneAggregate(AddPolicyToZoneAggregateContext context) {
        List<AggregateSnapshots> snapshots = aggregation.addPolicyToZones(context.getPolicyId(),
                        context.getZoneCodes(), context.getInsuredValue()).stream()
                .map(s -> Struct.access(s).as(AggregateSnapshots.class))
                .toList();
        context.setResult(snapshots);
        context.setCompleted();
    }

    @On(event = ScreenCatastropheCapacityContext.CDS_NAME)
    public void onScreenCatastropheCapacity(ScreenCatastropheCapacityContext context) {
        var submission = aggregation.screenCapacity(context.getSubmissionId(),
                context.getZoneCode());
        context.setResult(Struct.access(submission)
                .as(cds.gen.underwritingservice.Submissions.class));
        context.setCompleted();
    }

    // ─── Inspections ───

    @On(event = OrderPropertyInspectionContext.CDS_NAME)
    public void onOrderPropertyInspection(OrderPropertyInspectionContext context) {
        var inspection = inspections.order(context.getPolicyId(), context.getVendorName(),
                context.getContactPhone(), context.getUnoccupiedDays());
        context.setResult(Struct.access(inspection)
                .as(cds.gen.underwritingservice.Inspections.class));
        context.setCompleted();
    }

    @On(event = RecordNoAccessResultContext.CDS_NAME)
    public void onRecordNoAccessResult(RecordNoAccessResultContext context) {
        var inspection = inspections.recordNoAccess(context.getInspectionId(),
                context.getAttempts());
        context.setResult(Struct.access(inspection)
                .as(cds.gen.underwritingservice.Inspections.class));
        context.setCompleted();
    }

    @On(event = OrderLossControlSurveyContext.CDS_NAME)
    public void onOrderLossControlSurvey(OrderLossControlSurveyContext context) {
        var survey = surveys.order(context.getPolicyId(), context.getDwellingValue(),
                context.getDistanceToWaterFeet(), context.getPriorReportDate(),
                context.getPriorReportOpenRecommendations());
        context.setResult(Struct.access(survey)
                .as(cds.gen.underwritingservice.LossControlSurveys.class));
        context.setCompleted();
    }

    @On(event = RecordFireProtectionFindingContext.CDS_NAME)
    public void onRecordFireProtectionFinding(RecordFireProtectionFindingContext context) {
        var survey = surveys.recordFireProtectionFinding(context.getSurveyId(),
                context.getHydrantDistanceFeet(), context.getPriorProtectionClass(),
                context.getNewProtectionClass());
        context.setResult(Struct.access(survey)
                .as(cds.gen.underwritingservice.LossControlSurveys.class));
        context.setCompleted();
    }

    @On(event = ReviewInspectionFindingsContext.CDS_NAME)
    public void onReviewInspectionFindings(ReviewInspectionFindingsContext context) {
        var findings = reviews.review(context.getInspectionId(), context.getSquareFeet(),
                context.getRoofAgeYears(), context.getInspectedReplacementCost(),
                context.getReviewedBy(), context.getObservedHazard());
        context.setResult(Struct.access(findings)
                .as(cds.gen.underwritingservice.InspectionFindings.class));
        context.setCompleted();
    }

    @On(event = OpenRecommendationContext.CDS_NAME)
    public void onOpenRecommendation(OpenRecommendationContext context) {
        var recommendation = recommendations.open(context.getInspectionId(), context.getSeverity(),
                context.getDescription());
        context.setResult(Struct.access(recommendation).as(InspectionRecommendations.class));
        context.setCompleted();
    }

    @On(event = SubmitRecommendationProofContext.CDS_NAME)
    public void onSubmitRecommendationProof(SubmitRecommendationProofContext context) {
        var recommendation = recommendations.submitProof(context.getRecommendationId(),
                context.getDocumentType(), context.getFileName(), context.getDocumentDate());
        context.setResult(Struct.access(recommendation).as(InspectionRecommendations.class));
        context.setCompleted();
    }

    @On(event = ExpireRecommendationCurePeriodsContext.CDS_NAME)
    public void onExpireRecommendationCurePeriods(ExpireRecommendationCurePeriodsContext context) {
        List<InspectionRecommendations> expired = recommendations.expireCurePeriods().stream()
                .map(r -> Struct.access(r).as(InspectionRecommendations.class))
                .toList();
        context.setResult(expired);
        context.setCompleted();
    }
}
