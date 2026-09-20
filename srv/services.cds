using { insurance as db } from '../db/schema';

// Roles follow ARCHITECTURE.md "Auth": prospect, insured, agent, adjuster.
// Only [published] scenarios are exposed; [proposed] behavior is absent.

// Spec: specs/policy/auto-policy/quote-policy.feature.md
// Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
@path: '/quotes'
@(requires: 'authenticated-user')
service QuoteService {
  @readonly entity AutoQuotes as projection on db.AutoQuotes;
  @readonly entity HomeQuotes as projection on db.HomeQuotes;

  @(requires: 'prospect')
  action requestAutoQuote(vehicleMake : String,
                          vehicleModel : String,
                          vehicleYear : Integer,
                          driverName : String,
                          driverEmail : String,
                          driverPhone : String,
                          atFaultAccidents3y : Integer,
                          garagingZip : String) returns AutoQuotes;

  @(requires: 'prospect')
  action requestHomeQuote(address : String,
                          yearBuilt : Integer,
                          constructionType : String,
                          ownerName : String,
                          ownerEmail : String,
                          dwellingCoverage : Decimal(12, 2)) returns HomeQuotes;
}

// Spec: specs/policy/auto-policy/bind-policy.feature.md
// Spec: specs/policy/auto-policy/cancel-policy.feature.md
// Spec: specs/policy/auto-policy/endorse-policy.feature.md
// Spec: specs/policy/auto-policy/renew-policy.feature.md
@path: '/policies'
@(requires: 'authenticated-user')
service PolicyService {
  @readonly entity Policies            as projection on db.Policies;
  @readonly entity Installments        as projection on db.Installments;
  @readonly entity Endorsements        as projection on db.Endorsements;
  @readonly entity RenewalOffers       as projection on db.RenewalOffers;
  @readonly entity CancellationNotices as projection on db.CancellationNotices;
  @readonly entity Refunds             as projection on db.Refunds;

  @(requires: 'prospect')
  action bindPolicy(quoteId : UUID,
                    plan : String,
                    requestedStartDate : Date,
                    billingPlan : String,
                    paymentMethodToken : String) returns Policies;

  @(requires: 'insured')
  action requestCancellation(policyId : UUID,
                             cancellationDate : Date) returns Policies;

  @(requires: 'insured')
  action addVehicle(policyId : UUID,
                    make : String,
                    model : String,
                    modelYear : Integer) returns Policies;

  // Explicit issue action only — nightly issuance stays [proposed]
  @(requires: 'agent')
  action issueCancellationNotice(policyId : UUID) returns CancellationNotices;
}

// Spec: specs/claims/auto-accidents/record-car-accident.feature.md
// Spec: specs/claims/auto-accidents/assign-adjuster.feature.md
// Spec: specs/claims/auto-accidents/settle-collision-claim.feature.md
// Spec: specs/claims/home-claims/record-water-damage-claim.feature.md
@path: '/claims'
@(requires: 'authenticated-user')
service ClaimsService {
  @readonly entity Claims as projection on db.Claims;

  @(requires: 'insured')
  action reportAccident(policyId : UUID,
                        dateOfLoss : Date,
                        lossLocation : String,
                        description : String,
                        photos : array of String) returns Claims;

  // 24/7 claims hotline: a live agent records the accident on the insured's behalf
  @(requires: 'agent')
  action reportAccidentForInsured(policyId : UUID,
                                  dateOfLoss : Date,
                                  lossLocation : String,
                                  description : String,
                                  photos : array of String) returns Claims;

  @(requires: 'insured')
  action reportWaterDamage(policyId : UUID,
                           dateOfLoss : Date,
                           description : String,
                           causeOfLoss : String) returns Claims;

  @(requires: ['adjuster', 'agent'])
  action recordDamageEstimate(claimId : UUID,
                              estimatedDamage : Decimal(12, 2)) returns Claims;

  @(requires: 'adjuster')
  action recordRepairEstimate(claimId : UUID,
                              repairShopName : String,
                              amount : Decimal(12, 2)) returns Claims;

  @(requires: 'adjuster')
  action authorizeRepair(claimId : UUID) returns Claims;
}

// ─────────────────────────────────────────────────────────────────────────────
// Underwriting
// ─────────────────────────────────────────────────────────────────────────────

// Spec: specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
// Spec: specs/underwriting/eligibility/check-prior-loss-history.feature.md
// Spec: specs/underwriting/eligibility/detect-prior-cancellation.feature.md
// Spec: specs/underwriting/eligibility/verify-insurance-score.feature.md
// Spec: specs/underwriting/referrals/refer-to-underwriter.feature.md
// Spec: specs/underwriting/referrals/apply-referral-sla.feature.md
// Spec: specs/underwriting/referrals/escalate-authority-limit.feature.md
// Spec: specs/underwriting/referrals/record-underwriter-decision.feature.md
// Spec: specs/underwriting/appetite/define-underwriting-appetite.feature.md
// Spec: specs/underwriting/appetite/restrict-by-territory.feature.md
// Spec: specs/underwriting/appetite/apply-moratorium.feature.md
// Spec: specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
// Spec: specs/underwriting/inspections/order-property-inspection.feature.md
// Spec: specs/underwriting/inspections/order-loss-control-survey.feature.md
// Spec: specs/underwriting/inspections/review-inspection-findings.feature.md
// Spec: specs/underwriting/inspections/track-inspection-recommendations.feature.md
@path: '/underwriting'
@(requires: 'authenticated-user')
service UnderwritingService {
  @readonly entity Submissions               as projection on db.Submissions;
  @readonly entity EligibilityScreenings     as projection on db.EligibilityScreenings;
  @readonly entity ScreeningRuleResults      as projection on db.ScreeningRuleResults;
  @readonly entity LossHistoryReports        as projection on db.LossHistoryReports;
  @readonly entity LossHistoryEntries        as projection on db.LossHistoryEntries;
  @readonly entity PriorCoverageHistories    as projection on db.PriorCoverageHistories;
  @readonly entity InsuranceScores           as projection on db.InsuranceScores;
  @readonly entity AdverseActionNotices      as projection on db.AdverseActionNotices;
  @readonly entity Referrals                 as projection on db.Referrals;
  @readonly entity ReferralReasons           as projection on db.ReferralReasons;
  @readonly entity UnderwriterDecisions      as projection on db.UnderwriterDecisions;
  @readonly entity CounterOffers             as projection on db.CounterOffers;
  @readonly entity AuthorityEscalations      as projection on db.AuthorityEscalations;
  @readonly entity AppetiteEntries           as projection on db.AppetiteEntries;
  @readonly entity AppetiteEvaluations       as projection on db.AppetiteEvaluations;
  @readonly entity TerritoryRestrictions     as projection on db.TerritoryRestrictions;
  @readonly entity AgencyTerritoryQuotas     as projection on db.AgencyTerritoryQuotas;
  @readonly entity Moratoria                 as projection on db.Moratoria;
  @readonly entity BlockedRequests           as projection on db.BlockedRequests;
  @readonly entity CatastropheZones          as projection on db.CatastropheZones;
  @readonly entity AggregateSnapshots        as projection on db.AggregateSnapshots;
  @readonly entity Inspections               as projection on db.Inspections;
  @readonly entity LossControlSurveys        as projection on db.LossControlSurveys;
  @readonly entity InspectionFindings        as projection on db.InspectionFindings;
  @readonly entity InspectionRecommendations as projection on db.InspectionRecommendations;

  // ─── Eligibility ───
  @(requires: 'agent')
  action submitApplication(lineOfBusiness : String,
                           state : String,
                           territoryCode : String,
                           garagingZip : String,
                           riskAddress : String,
                           applicantName : String,
                           applicantEmail : String,
                           applicantDob : Date,
                           agencyId : String,
                           dwellingCoverage : Decimal(12, 2)) returns Submissions;

  @(requires: 'agent')
  action screenEligibility(submissionId : UUID,
                           validLicense : Boolean,
                           lossHistoryPasses : Boolean,
                           insuranceScorePasses : Boolean,
                           priorCancellationPasses : Boolean,
                           residencyInState : Boolean) returns EligibilityScreenings;

  @(requires: 'agent')
  action orderLossHistory(submissionId : UUID) returns LossHistoryReports;

  @(requires: 'agent')
  action recordLossHistoryEntry(reportId : UUID,
                                claimNumber : String,
                                lossDate : Date,
                                perilCode : String,
                                catastropheCode : String,
                                paidAmount : Decimal(12, 2)) returns LossHistoryEntries;

  @(requires: 'agent')
  action evaluateLossHistory(submissionId : UUID) returns Submissions;

  @(requires: 'agent')
  action evaluatePriorCoverage(submissionId : UUID,
                               priorCarrierName : String,
                               coverageEndedOn : Date,
                               requestedEffectiveDate : Date,
                               classification : String,
                               nonPaymentCount12m : Integer,
                               cancellationDate : Date) returns PriorCoverageHistories;

  @(requires: 'agent')
  action orderInsuranceScore(submissionId : UUID,
                             score : Integer,
                             noHit : Boolean,
                             modelVersion : String,
                             reportingAgency : String) returns InsuranceScores;

  @(requires: 'agent')
  action issueAdverseActionNotice(submissionId : UUID) returns AdverseActionNotices;

  // ─── Referrals ───
  @(requires: 'agent')
  action referToUnderwriter(submissionId : UUID,
                            referralType : String,
                            queueName : String) returns Referrals;

  @(requires: 'agent')
  action assignReferral(referralId : UUID, assignedTo : String) returns Referrals;

  @(requires: 'agent')
  action awaitAgentInformation(referralId : UUID) returns Referrals;

  @(requires: 'agent')
  action resumeReferralClock(referralId : UUID) returns Referrals;

  @(requires: 'agent')
  action runSlaMonitor() returns array of Referrals;

  @(requires: 'agent')
  action recordUnderwriterDecision(referralId : UUID,
                                   outcome : String,
                                   underwriterName : String,
                                   reasonCode : String,
                                   narrative : String,
                                   conditionDescription : String,
                                   conditionDueInDays : Integer,
                                   counterOfferDescription : String,
                                   revisedPremium : Decimal(12, 2)) returns UnderwriterDecisions;

  @(requires: 'agent')
  action escalateAuthorityLimit(referralId : UUID,
                                escalationType : String,
                                requestedAmount : Decimal(14, 2),
                                requestedCreditPct : Decimal(5, 2),
                                underwriterRole : String,
                                recommendation : String,
                                recommendedOutcome : String,
                                justification : String) returns AuthorityEscalations;

  // ─── Appetite, territory, moratorium, catastrophe aggregation ───
  @(requires: 'agent')
  action publishAppetiteEntry(entryId : UUID,
                              publishedBy : String,
                              effectiveDate : Date) returns AppetiteEntries;

  @(requires: 'agent')
  action evaluateAppetite(submissionId : UUID,
                          featureName : String) returns AppetiteEvaluations;

  @(requires: 'agent')
  action screenTerritory(submissionId : UUID) returns Submissions;

  @(requires: 'agent')
  action reopenTerritory(territoryCode : String,
                         lineOfBusiness : String,
                         reopenDate : Date) returns TerritoryRestrictions;

  @(requires: 'agent')
  action openMoratorium(moratoriumId : String,
                        peril : String,
                        areaType : String,
                        areaCodes : array of String) returns Moratoria;

  @(requires: 'agent')
  action cancelStormWatch(moratoriumId : UUID) returns Moratoria;

  @(requires: 'agent')
  action liftDueMoratoria() returns array of Moratoria;

  @(requires: 'insured')
  action requestCoverageIncrease(policyId : UUID,
                                 requestedLimit : Decimal(14, 2),
                                 zipCode : String) returns BlockedRequests;

  @(requires: 'agent')
  action addPolicyToZoneAggregate(policyId : UUID,
                                  zoneCodes : array of String,
                                  insuredValue : Decimal(14, 2)) returns array of AggregateSnapshots;

  @(requires: 'agent')
  action screenCatastropheCapacity(submissionId : UUID,
                                   zoneCode : String) returns Submissions;

  // ─── Inspections ───
  @(requires: 'agent')
  action orderPropertyInspection(policyId : UUID,
                                 vendorName : String,
                                 contactPhone : String,
                                 unoccupiedDays : Integer) returns Inspections;

  @(requires: 'agent')
  action recordNoAccessResult(inspectionId : UUID, attempts : Integer) returns Inspections;

  @(requires: 'agent')
  action orderLossControlSurvey(policyId : UUID,
                                dwellingValue : Decimal(14, 2),
                                distanceToWaterFeet : Integer,
                                priorReportDate : Date,
                                priorReportOpenRecommendations : Integer) returns LossControlSurveys;

  @(requires: 'agent')
  action recordFireProtectionFinding(surveyId : UUID,
                                     hydrantDistanceFeet : Integer,
                                     priorProtectionClass : Integer,
                                     newProtectionClass : Integer) returns LossControlSurveys;

  @(requires: 'agent')
  action reviewInspectionFindings(inspectionId : UUID,
                                  squareFeet : Integer,
                                  roofAgeYears : Integer,
                                  inspectedReplacementCost : Decimal(14, 2),
                                  reviewedBy : String,
                                  observedHazard : String) returns InspectionFindings;

  @(requires: 'agent')
  action openRecommendation(inspectionId : UUID,
                            severity : String,
                            description : String) returns InspectionRecommendations;

  @(requires: 'agent')
  action submitRecommendationProof(recommendationId : UUID,
                                   documentType : String,
                                   fileName : String,
                                   documentDate : Date) returns InspectionRecommendations;

  @(requires: 'agent')
  action expireRecommendationCurePeriods() returns array of InspectionRecommendations;
}
