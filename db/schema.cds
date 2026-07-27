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
