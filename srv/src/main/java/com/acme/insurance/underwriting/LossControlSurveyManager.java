package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

import cds.gen.insurance.LossControlSurveys;
import cds.gen.insurance.LossControlSurveys_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * The engineering-led survey ordered on higher-hazard or higher-value risks,
 * distinct from the routine property inspection.
 *
 * Spec: specs/underwriting/inspections/order-loss-control-survey.feature.md
 */
@Component
public class LossControlSurveyManager {

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public LossControlSurveyManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Survey ordered on a high-value coastal dwelling" — a $3,200,000 dwelling
     * within 1,500 feet of open water gets an engineering survey with a 45-day
     * due date, scoped to wind mitigation features and roof attachment.
     *
     * "Survey waived when a recent report exists" — a survey completed within
     * the last 24 months with no open recommendations waives the order; the
     * prior report is attached and the waiver recorded with the prior date.
     */
    public LossControlSurveys order(String policyId, BigDecimal dwellingValue,
            Integer distanceToWaterFeet, LocalDate priorReportDate,
            Integer priorReportOpenRecommendations) {
        Policies policy = loadPolicy(policyId);
        LocalDate today = LocalDate.now(clock);

        LossControlSurveys survey = LossControlSurveys.create();
        survey.setId(UUID.randomUUID().toString());
        survey.setPolicyId(policyId);
        survey.setOrderedOn(today);
        survey.setDwellingValue(dwellingValue);
        survey.setDistanceToWaterFeet(distanceToWaterFeet);
        survey.setPriorReportDate(priorReportDate);

        boolean recentClean = priorReportDate != null
                && ChronoUnit.MONTHS.between(priorReportDate, today) < Statuses.SURVEY_WAIVER_MONTHS
                && (priorReportOpenRecommendations == null || priorReportOpenRecommendations == 0);

        if (recentClean) {
            survey.setStatus(Statuses.INSPECTION_WAIVED);
            survey.setWaived(true);
            survey.setWaiverReason("prior engineering survey dated " + priorReportDate
                    + " with no open recommendations");
        } else {
            survey.setStatus(Statuses.INSPECTION_ORDERED);
            survey.setWaived(false);
            survey.setDueDate(today.plusDays(Statuses.SURVEY_DUE_DAYS));
            boolean coastal = distanceToWaterFeet != null
                    && distanceToWaterFeet <= Statuses.COASTAL_DISTANCE_FEET;
            survey.setScopeWindMitigation(coastal);
            survey.setScopeRoofAttachment(coastal);
        }

        persistence.run(Insert.into(LossControlSurveys_.class).entry(survey));
        return load(survey.getId());
    }

    /** A vacant dwelling opens a survey alongside the property inspection. */
    LossControlSurveys orderForVacancy(Policies policy) {
        LocalDate today = LocalDate.now(clock);
        LossControlSurveys survey = LossControlSurveys.create();
        survey.setId(UUID.randomUUID().toString());
        survey.setPolicyId(policy.getId());
        survey.setStatus(Statuses.INSPECTION_ORDERED);
        survey.setOrderedOn(today);
        survey.setDueDate(today.plusDays(Statuses.SURVEY_DUE_DAYS));
        survey.setWaived(false);
        survey.setDwellingValue(policy.getDwellingCoverage());
        persistence.run(Insert.into(LossControlSurveys_.class).entry(survey));
        return survey;
    }

    /**
     * "Survey uncovers an unprotected fire exposure" — a hydrant 1,800 feet from
     * the dwelling moves the risk from protection class 4 to class 9 at the
     * anniversary, with the premium change disclosed on 45 days notice.
     */
    public LossControlSurveys recordFireProtectionFinding(String surveyId,
            Integer hydrantDistanceFeet, Integer priorProtectionClass, Integer newProtectionClass) {
        LossControlSurveys survey = load(surveyId);
        Policies policy = loadPolicy(survey.getPolicyId());

        survey.setStatus(Statuses.INSPECTION_COMPLETED);
        survey.setHydrantDistanceFeet(hydrantDistanceFeet);
        survey.setPriorProtectionClass(priorProtectionClass);
        survey.setNewProtectionClass(newProtectionClass);
        // The reclassification takes effect at the policy anniversary.
        survey.setProtectionClassEffectiveOn(policy.getExpirationDate());
        survey.setNoticeDays(Statuses.COVERAGE_CHANGE_NOTICE_DAYS);
        persistence.run(Update.entity(LossControlSurveys_.class).entry(survey));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Change to your protection class",
                "A loss control survey found the nearest fire hydrant is " + hydrantDistanceFeet
                        + " feet from the dwelling. Your protection class changes from "
                        + priorProtectionClass + " to " + newProtectionClass + " effective "
                        + policy.getExpirationDate() + ", with "
                        + Statuses.COVERAGE_CHANGE_NOTICE_DAYS + " days notice."));

        return load(surveyId);
    }

    public LossControlSurveys load(String surveyId) {
        return persistence.run(Select.from(LossControlSurveys_.class)
                .where(s -> s.ID().eq(surveyId)))
                .first(LossControlSurveys.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Loss control survey not found: " + surveyId));
    }

    private Policies loadPolicy(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));
    }
}
