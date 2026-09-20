namespace insurance;

using { cuid, managed } from '@sap/cds/common';

// ─────────────────────────────────────────────────────────────────────────────
// Rating tables — CSV seed data under db/data/, never constants in code
// (see ARCHITECTURE.md "Rate data")
// ─────────────────────────────────────────────────────────────────────────────

// Spec: specs/policy/auto-policy/quote-policy.feature.md
entity AutoPlanRates {
  key plan            : String(20);   // Liability Only | Standard | Full
  bodilyInjuryLimit   : String(10);   // e.g. 100/300
  collisionDeductible : Decimal(9, 2); // null = collision not included
  sixMonthPremium     : Decimal(9, 2);
  displayOrder        : Integer;
}

// Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
entity HomeRates {
  key dwellingCoverage : Decimal(12, 2);
  key deductible       : Decimal(9, 2);
  annualPremium        : Decimal(9, 2);
  displayOrder         : Integer;
}

// Spec: specs/billing/collect-premium.feature.md (installment plan table)
entity BillingPlans {
  key plan             : String(20);  // Paid in full | Two-pay | Monthly autopay
  payments             : Integer;
  serviceFeePerPayment : Decimal(9, 2);
}

// ─────────────────────────────────────────────────────────────────────────────
// Quoting
// ─────────────────────────────────────────────────────────────────────────────

// Spec: specs/policy/auto-policy/quote-policy.feature.md
entity AutoQuotes : cuid, managed {
  status             : String(10);   // Active | Declined | Bound
  declineReason      : String(100);
  vehicleMake        : String(40);
  vehicleModel       : String(40);
  vehicleYear        : Integer;
  driverName         : String(80);
  driverEmail        : String(120);
  driverPhone        : String(30);
  atFaultAccidents3y : Integer;
  garagingZip        : String(10);
  termMonths         : Integer;
  options            : Composition of many AutoQuoteOptions
                         on options.quote = $self;
}

entity AutoQuoteOptions : cuid {
  quote               : Association to AutoQuotes;
  plan                : String(20);
  bodilyInjuryLimit   : String(10);
  collisionDeductible : Decimal(9, 2);
  premium             : Decimal(9, 2); // six-month premium
  displayOrder        : Integer;
}

// Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
entity HomeQuotes : cuid, managed {
  status           : String(10);     // Active
  address          : String(120);
  yearBuilt        : Integer;
  constructionType : String(30);
  ownerName        : String(80);
  ownerEmail       : String(120);
  dwellingCoverage : Decimal(12, 2);
  options          : Composition of many HomeQuoteOptions
                       on options.quote = $self;
}

entity HomeQuoteOptions : cuid {
  quote         : Association to HomeQuotes;
  deductible    : Decimal(9, 2);
  annualPremium : Decimal(9, 2);
  displayOrder  : Integer;
}

// ─────────────────────────────────────────────────────────────────────────────
// Policies
// ─────────────────────────────────────────────────────────────────────────────

// Spec: specs/policy/auto-policy/bind-policy.feature.md
entity Policies : cuid, managed {
  policyNumber       : String(20);
  lineOfBusiness     : String(10);   // Auto | Home
  formType           : String(10);   // e.g. HO-3 for homeowners
  status             : String(20);   // InForce | Cancelled
  plan               : String(20);   // rating plan of the policy
  bodilyInjuryLimit  : String(10);
  deductible         : Decimal(9, 2); // auto: collision deductible; home: all perils
  dwellingCoverage   : Decimal(12, 2);
  premium            : Decimal(12, 2); // current full-term premium incl. endorsements
  termMonths         : Integer;      // 6 for auto, 12 for home
  effectiveDate      : Date;
  expirationDate     : Date;
  cancellationDate   : Date;
  cancellationReason : String(60);
  namedInsuredName   : String(80);
  namedInsuredEmail  : String(120);
  namedInsuredPhone  : String(30);
  garagingZip        : String(10);
  billingPlan        : String(20);   // key into BillingPlans
  paymentMethodToken : String(60);   // original payment method at the gateway
  quote              : Association to AutoQuotes;
  vehicles           : Composition of many PolicyVehicles
                         on vehicles.policy = $self;
  installments       : Composition of many Installments
                         on installments.policy = $self;
}

entity PolicyVehicles : cuid {
  policy    : Association to Policies;
  make      : String(40);
  model     : String(40);
  modelYear : Integer;
  addedOn   : Date;
}

// Spec: specs/billing/collect-premium.feature.md
entity Installments : cuid {
  policy        : Association to Policies;
  seq           : Integer;
  dueDate       : Date;
  premiumAmount : Decimal(9, 2);
  serviceFee    : Decimal(9, 2);
  totalAmount   : Decimal(9, 2);
  status        : String(20);        // Scheduled | Paid | PastDue
  paidOn        : Date;
  retryOn       : Date;              // next autopay retry after a decline
  graceEndsOn   : Date;              // end of the 10-day grace period
}

// Spec: specs/policy/auto-policy/endorse-policy.feature.md
entity Endorsements : cuid, managed {
  policy            : Association to Policies;
  endorsementType   : String(30);    // AddVehicle
  endorsementDate   : Date;
  additionalPremium : Decimal(9, 2);
  description       : String(200);
}

// Spec: specs/policy/auto-policy/renew-policy.feature.md
entity RenewalOffers : cuid, managed {
  policy            : Association to Policies;
  offeredOn         : Date;
  newEffectiveDate  : Date;
  newExpirationDate : Date;
  plan              : String(20);
  premium           : Decimal(9, 2); // at current rates
  status            : String(15);    // Offered
}

// Spec: specs/policy/auto-policy/cancel-policy.feature.md — modeled minimally;
// the nightly issuance automation stays [proposed] (see ARCHITECTURE.md)
entity CancellationNotices : cuid, managed {
  policy           : Association to Policies;
  issuedOn         : Date;
  cancellationDate : Date;           // issuedOn + 14-day notice period
  pastDueAmount    : Decimal(9, 2);
  status           : String(15);     // Issued | Executed
}

// Spec: specs/billing/refund-unearned-premium.feature.md
entity Refunds : cuid, managed {
  policy     : Association to Policies;
  amount     : Decimal(9, 2);
  refundedOn : Date;
  method     : String(40);           // 'original payment method'
}

// ─────────────────────────────────────────────────────────────────────────────
// Claims
// ─────────────────────────────────────────────────────────────────────────────

// Specs: specs/claims/auto-accidents/record-car-accident.feature.md,
//        specs/claims/auto-accidents/assign-adjuster.feature.md,
//        specs/claims/auto-accidents/settle-collision-claim.feature.md,
//        specs/claims/home-claims/record-water-damage-claim.feature.md
entity Claims : cuid, managed {
  claimNumber              : String(20);
  policy                   : Association to Policies;
  claimType                : String(30); // AutoCollision | HomeWater
  status                   : String(40); // 'Open — awaiting adjuster' | 'Open — assigned' | 'Repair authorized'
  channel                  : String(10); // Online | Hotline
  dateOfLoss               : Date;
  lossLocation             : String(120);
  description              : String(500);
  coverageClassification   : String(60); // e.g. 'sudden and accidental water damage'
  estimatedDamage          : Decimal(12, 2);
  adjusterRoute            : String(40); // routing per assign-adjuster severity table
  supervisorReviewRequired : Boolean default false;
  deductible               : Decimal(9, 2); // snapshot from the policy
  repairShopName           : String(80);
  repairEstimateAmount     : Decimal(12, 2);
  paidToShopAmount         : Decimal(12, 2);
  mitigationVendorName     : String(80);
  mitigationVendorOfferedAt : Timestamp;
  photos                   : Composition of many ClaimPhotos
                               on photos.claim = $self;
}

entity ClaimPhotos : cuid {
  claim    : Association to Claims;
  fileName : String(120);
}

// ─────────────────────────────────────────────────────────────────────────────
// Underwriting
// ─────────────────────────────────────────────────────────────────────────────

// The new-business submission is the spine of the underwriting domain: it is
// screened (eligibility), gated (appetite, territory, moratorium, catastrophe
// aggregation), and may be referred to an underwriter for a decision.
// Spec: specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
entity Submissions : cuid, managed {
  submissionNumber   : String(20);
  lineOfBusiness     : String(20);    // Auto | Home | Umbrella
  state              : String(2);
  territoryCode      : String(10);
  garagingZip        : String(10);
  riskAddress        : String(120);
  applicantName      : String(80);
  applicantEmail     : String(120);
  applicantDob       : Date;
  agencyId           : String(20);
  status             : String(30);    // Screening | Eligible | Referred | Declined | Bindable | Held
  declineReason      : String(120);
  dwellingCoverage   : Decimal(12, 2);
  requestedLimit     : Decimal(14, 2); // e.g. umbrella limit requested
  quotedOn           : Date;
  screenings         : Composition of many EligibilityScreenings
                         on screenings.submission = $self;
  referrals          : Composition of many Referrals
                         on referrals.submission = $self;
}

// Spec: specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
entity EligibilityScreenings : cuid, managed {
  submission       : Association to Submissions;
  screenedOn       : Date;
  ruleSetVersion   : String(20);      // stamped on the result
  outcome          : String(20);      // Eligible | Referred | Declined
  expiresOn        : Date;            // screenedOn + 60 days
  superseded       : Boolean default false;
  results          : Composition of many ScreeningRuleResults
                       on results.screening = $self;
}

entity ScreeningRuleResults : cuid {
  screening       : Association to EligibilityScreenings;
  ruleName        : String(40);       // Valid license | Loss history | Insurance score | ...
  passed          : Boolean;
  outcomeOnFailure : String(10);      // decline | refer
  overridable     : Boolean;
  evaluatedValue  : String(200);      // the values behind the decision
}

// Screening rule table — CSV seed data, see the table in
// specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
entity ScreeningRules {
  key ruleName         : String(40);
  outcomeOnFailure     : String(10);  // decline | refer
  overridable          : Boolean;
  displayOrder         : Integer;
}

// Spec: specs/underwriting/eligibility/check-prior-loss-history.feature.md
entity LossHistoryReports : cuid, managed {
  submission      : Association to Submissions;
  reportType      : String(20);       // CLUE property
  yearsRequested  : Integer;          // 5-year CLUE property report
  orderedOn       : Date;
  matchedByName   : Boolean default false;
  matchedByDob    : Boolean default false;
  entries         : Composition of many LossHistoryEntries
                      on entries.report = $self;
}

entity LossHistoryEntries : cuid {
  report            : Association to LossHistoryReports;
  claimNumber       : String(30);
  lossDate          : Date;
  perilCode         : String(30);     // Water | Hail | Fire | Theft
  catastropheCode   : String(20);     // set when the loss is weather/CAT coded
  paidAmount        : Decimal(12, 2);
  countsTowardFrequency : Boolean default true;
  excludedReason    : String(60);     // e.g. 'weather-only catastrophe code'
}

// Spec: specs/underwriting/eligibility/detect-prior-cancellation.feature.md
entity PriorCoverageHistories : cuid, managed {
  submission            : Association to Submissions;
  priorCarrierName      : String(80);
  coverageEndedOn       : Date;
  requestedEffectiveDate : Date;
  lapseDays             : Integer;
  continuousCoverageDiscount : Boolean default true;
  ratingTier            : String(40);  // e.g. 'no-prior-insurance'
  classification        : String(60);  // insured-requested | non-payment | misrepresentation
  nonPaymentCount12m    : Integer default 0;
  paymentInAdvanceRequired : Boolean default false;
  installmentsSuppressed : Boolean default false;
  cancellationDate      : Date;
}

// Spec: specs/underwriting/eligibility/verify-insurance-score.feature.md
entity InsuranceScores : cuid, managed {
  submission       : Association to Submissions;
  score            : Integer;         // null when the vendor returns no hit
  noHit            : Boolean default false;
  tier             : String(5);       // A | B | C | D | E | Neutral
  tierFactor       : Decimal(5, 3);
  modelVersion     : String(20);
  orderedOn        : Date;
  reportingAgency  : String(80);
  adverseAction    : Association to AdverseActionNotices;
}

// Insurance score tier table — CSV seed data
entity ScoreTiers {
  key tier       : String(5);
  minScore       : Integer;
  maxScore       : Integer;
  tierFactor     : Decimal(5, 3);
  neutral        : Boolean default false;
}

// Spec: specs/underwriting/eligibility/verify-insurance-score.feature.md
entity AdverseActionNotices : cuid, managed {
  submission       : Association to Submissions;
  reportingAgency  : String(80);
  mailByDate       : Date;            // within 30 days of issue
  mailedOn         : Date;
  reasonCodes      : Composition of many AdverseActionReasons
                       on reasonCodes.notice = $self;
}

entity AdverseActionReasons : cuid {
  notice   : Association to AdverseActionNotices;
  rank     : Integer;                 // 1..4, the top four reason codes
  code     : String(10);
  narrative : String(200);
}

// Spec: specs/underwriting/referrals/refer-to-underwriter.feature.md
entity Referrals : cuid, managed {
  submission        : Association to Submissions;
  referralType      : String(30);     // Standard personal | Over-limit authority | ...
  status            : String(30);     // Referred | Assigned | AwaitingAgent | Decided | Escalated
  queueName         : String(60);     // e.g. 'Texas property'
  originalQueueName : String(60);     // retained when reassigned to overflow
  reassignmentReason : String(80);
  assignedTo        : String(80);
  queuedAt          : Timestamp;
  slaTargetHours    : Integer;
  slaDueAt          : Timestamp;
  slaStatus         : String(20);     // Open | Met | Breached
  slaPausedAt       : Timestamp;
  slaPausedHours    : Decimal(6, 2);  // accumulated suspended business hours
  escalatedTo       : String(60);
  reasons           : Composition of many ReferralReasons
                        on reasons.referral = $self;
  decision          : Association to UnderwriterDecisions;
}

entity ReferralReasons : cuid {
  referral       : Association to Referrals;
  priority       : Integer;           // reasons listed in priority order
  reasonCode     : String(40);
  evaluatedValue : String(200);
}

// SLA targets — CSV seed data, see the table in
// specs/underwriting/referrals/apply-referral-sla.feature.md
entity ReferralSlaTargets {
  key referralType    : String(30);
  targetHours         : Integer;
  firstReminderHours  : Integer;
  breachEscalation    : String(40);
  businessDays        : Boolean default false; // 'Complex commercial' targets days
}

// Spec: specs/underwriting/referrals/record-underwriter-decision.feature.md
entity UnderwriterDecisions : cuid, managed {
  referral          : Association to Referrals;
  outcome           : String(30);     // Accept | AcceptWithConditions | CounterOffer | Decline
  underwriterName   : String(80);
  decidedAt         : Timestamp;
  reasonCode        : String(40);
  narrative         : String(500);
  conditions        : Composition of many DecisionConditions
                        on conditions.decision = $self;
  counterOffer      : Association to CounterOffers;
}

entity DecisionConditions : cuid {
  decision      : Association to UnderwriterDecisions;
  description   : String(200);
  dueInDays     : Integer;
  printedOnBinder : Boolean default true;
}

entity CounterOffers : cuid, managed {
  decision              : Association to UnderwriterDecisions;
  description           : String(200); // e.g. '2% named storm deductible'
  revisedPremium        : Decimal(12, 2);
  originalQuoteValidUntil : Date;      // original stays available 5 days
}

// Spec: specs/underwriting/referrals/escalate-authority-limit.feature.md
entity AuthorityEscalations : cuid, managed {
  referral            : Association to Referrals;
  escalationType      : String(30);   // Limit | DiscretionaryCredit
  requestedAmount     : Decimal(14, 2);
  authorityLimit      : Decimal(14, 2);
  requestedCreditPct  : Decimal(5, 2);
  permittedCreditPct  : Decimal(5, 2);
  escalatedTo         : String(60);   // regional underwriting manager | ...
  recommendation      : String(500);  // carried forward to the approver
  recommendedOutcome  : String(20);   // Approve | Decline
  justification       : String(500);  // competitive justification
  approverMayDecide   : Boolean default true;
  status              : String(20);   // Open | Approved | Modified | Rejected
}

// Authority limits — CSV seed data
entity AuthorityLimits {
  key roleName        : String(40);   // underwriter | regional underwriting manager
  limitAmount         : Decimal(14, 2);
  creditBandPct       : Decimal(5, 2);
  escalatesTo         : String(40);
}

// Spec: specs/underwriting/appetite/define-underwriting-appetite.feature.md
entity AppetiteEntries : cuid, managed {
  name              : String(80);
  state             : String(2);      // null = countrywide
  lineOfBusiness    : String(20);
  constructionType  : String(30);
  maximumLimit      : Decimal(14, 2);
  disposition       : String(20);     // Permit | Exclude | Refer
  featureName       : String(80);     // e.g. 'fuel oil tank below grade'
  status            : String(15);     // Draft | Published
  effectiveDate     : Date;
  publishedBy       : String(80);
  publishedAt       : Timestamp;
}

// Spec: specs/underwriting/appetite/define-underwriting-appetite.feature.md
entity AppetiteEvaluations : cuid, managed {
  submission     : Association to Submissions;
  appetiteEntry  : Association to AppetiteEntries;
  outcome        : String(20);        // InAppetite | Refer | Decline
  reason         : String(120);       // 'outside published appetite'
  featureNamed   : String(120);       // the out-of-appetite feature on a referral
  evaluatedOn    : Date;
}

// Spec: specs/underwriting/appetite/restrict-by-territory.feature.md
entity TerritoryRestrictions : cuid, managed {
  territoryCode      : String(10);
  lineOfBusiness     : String(20);
  status             : String(20);    // Closed | Open
  closureReason      : String(80);    // 'rate inadequacy'
  effectiveDate      : Date;
  reopenDate         : Date;          // set when a rate filing is approved
  pendingRateFilingPct : Decimal(5, 2);
}

// Spec: specs/underwriting/appetite/restrict-by-territory.feature.md
entity AgencyTerritoryQuotas : cuid, managed {
  agencyId        : String(20);
  territoryCode   : String(10);
  monthlyQuota    : Integer;
  periodMonth     : String(7);        // YYYY-MM
  usedCount       : Integer default 0;
  principalEmail  : String(120);
}

// Spec: specs/underwriting/appetite/apply-moratorium.feature.md
entity Moratoria : cuid, managed {
  moratoriumId       : String(20);
  peril              : String(20);    // NamedStorm | Wildfire | Flood
  status             : String(15);    // Open | Lifted
  openedAt           : Timestamp;
  scheduledLiftAt    : Timestamp;     // watch cancelled + 72 hours
  liftedAt           : Timestamp;
  manualHold         : Boolean default false;
  watchCancelledAt   : Timestamp;
  areas              : Composition of many MoratoriumAreas
                         on areas.moratorium = $self;
}

entity MoratoriumAreas : cuid {
  moratorium  : Association to Moratoria;
  areaType    : String(10);           // County | Zip
  areaCode    : String(20);
}

// Endorsements blocked by a moratorium are queued for automatic reprocessing
// Spec: specs/underwriting/appetite/apply-moratorium.feature.md
entity BlockedRequests : cuid, managed {
  moratorium      : Association to Moratoria;
  policy          : Association to Policies;
  requestType     : String(30);       // CoverageIncrease
  requestedLimit  : Decimal(14, 2);
  status          : String(20);       // Queued | Reprocessed
  queuedOn        : Date;
  reprocessedOn   : Date;
}

// Spec: specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
entity CatastropheZones : cuid, managed {
  zoneCode          : String(20);
  peril             : String(20);     // Wind | Earthquake
  capacityAmount    : Decimal(16, 2);
  aggregateTiv      : Decimal(16, 2); // total insured value in the zone
  softStopPct       : Decimal(5, 2);  // 90%
  hardStopPct       : Decimal(5, 2);  // 98%
  reopenReviewDate  : Date;
  snapshots         : Composition of many AggregateSnapshots
                        on snapshots.zone = $self;
}

entity AggregateSnapshots : cuid {
  zone          : Association to CatastropheZones;
  policy        : Association to Policies;
  addedTiv      : Decimal(14, 2);
  aggregateTiv  : Decimal(16, 2);     // running total after the change
  takenAt       : Timestamp;          // timestamped for the nightly reinsurance report
}

// Zone utilization bands — CSV seed data, see the table in
// specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
entity ZoneUtilizationBands {
  key bandName       : String(20);
  minUtilizationPct  : Decimal(5, 2);
  maxUtilizationPct  : Decimal(5, 2);
  newBusiness        : String(15);    // open | restricted | closed
  renewals           : String(15);    // open | reviewed
  approvalRequired   : String(30);    // none | underwriter | chief underwriter
}

// Spec: specs/underwriting/inspections/order-property-inspection.feature.md
entity Inspections : cuid, managed {
  policy            : Association to Policies;
  submission        : Association to Submissions;
  inspectionType    : String(30);     // exterior only | interior + exterior | engineer walkthrough
  status            : String(20);     // Ordered | Completed | NoAccess | Waived
  vendorName        : String(80);
  orderedOn         : Date;
  orderDueBy        : Date;           // business-day SLA for placing the order
  vendorDueDate     : Date;           // 21 / 30 / 45 days per the value band
  riskAddress       : String(120);
  contactPhone      : String(30);
  appointmentRequested : Boolean default false;
  interiorAccessRequired : Boolean default false;
  vacancyNoted      : Boolean default false;
  accessAttempts    : Integer default 0;
  completedOn       : Date;
  findings          : Association to InspectionFindings;
}

// Inspection thresholds — CSV seed data, see the table in
// specs/underwriting/inspections/order-property-inspection.feature.md
entity InspectionThresholds {
  key bandName      : String(30);
  minDwellingLimit  : Decimal(14, 2);
  maxDwellingLimit  : Decimal(14, 2);
  inspectionType    : String(30);
  vendorDueDays     : Integer;        // null when no inspection is required
}

// Spec: specs/underwriting/inspections/order-loss-control-survey.feature.md
entity LossControlSurveys : cuid, managed {
  policy              : Association to Policies;
  status              : String(20);   // Ordered | Completed | Waived
  orderedOn           : Date;
  dueDate             : Date;         // 45-day due date
  scopeWindMitigation : Boolean default false;
  scopeRoofAttachment : Boolean default false;
  waived              : Boolean default false;
  waiverReason        : String(120);
  priorReportDate     : Date;
  distanceToWaterFeet : Integer;
  dwellingValue       : Decimal(14, 2);
  hydrantDistanceFeet : Integer;
  priorProtectionClass : Integer;
  newProtectionClass  : Integer;
  protectionClassEffectiveOn : Date;  // at the anniversary
  noticeDays          : Integer;      // 45 days notice of a premium change
}

// Spec: specs/underwriting/inspections/review-inspection-findings.feature.md
entity InspectionFindings : cuid, managed {
  inspection            : Association to Inspections;
  policy                : Association to Policies;
  squareFeet            : Integer;
  roofAgeYears          : Integer;
  inspectedReplacementCost : Decimal(14, 2);
  ratedDwellingLimit    : Decimal(14, 2);
  variancePct           : Decimal(6, 2);
  disposition           : String(40); // 'no action required' | LimitIncrease | Exclusion | NonRenewal
  reviewedBy            : String(80);
  reviewedOn            : Date;
  limitIncreasedTo      : Decimal(14, 2);
  changeEffectiveOn     : Date;       // at the next anniversary
  noticeDays            : Integer;    // 45 days notice
  exclusionEndorsement  : String(80); // e.g. 'trampoline liability exclusion'
  exclusionRemovableBy  : String(200);
  nonRenewalReason      : String(120);
  photosRetained        : Boolean default false;
}

// Spec: specs/underwriting/inspections/track-inspection-recommendations.feature.md
entity InspectionRecommendations : cuid, managed {
  inspection        : Association to Inspections;
  policy            : Association to Policies;
  severity          : String(20);     // Critical | Advisory
  description       : String(200);
  status            : String(20);     // Open | Complete | Declined
  openedOn          : Date;
  curePeriodDays    : Integer;        // 30 for critical
  cureDueOn         : Date;
  closedOn          : Date;
  closureReason     : String(60);     // 'declined — advisory, no coverage consequence'
  insuredNotifiedInWriting : Boolean default false;
  nonRenewalFlagged : Boolean default false;
  carriedForward    : Boolean default false;
  proofDocuments    : Composition of many RecommendationProofs
                        on proofDocuments.recommendation = $self;
}

entity RecommendationProofs : cuid {
  recommendation : Association to InspectionRecommendations;
  documentType   : String(40);        // Photograph | Invoice
  fileName       : String(120);
  documentDate   : Date;
}
