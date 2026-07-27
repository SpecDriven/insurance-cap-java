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
