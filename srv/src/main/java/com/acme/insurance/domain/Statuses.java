package com.acme.insurance.domain;

/** Status and routing literals; claim statuses/routes quote the specs verbatim. */
public final class Statuses {

    private Statuses() {
    }

    // Quotes
    public static final String QUOTE_ACTIVE = "Active";
    public static final String QUOTE_DECLINED = "Declined";
    public static final String QUOTE_BOUND = "Bound";

    // specs/policy/auto-policy/quote-policy.feature.md — "Decline high-risk driver"
    public static final String DECLINE_REASON_UNDERWRITING = "does not meet underwriting guidelines";

    // Policies
    public static final String POLICY_IN_FORCE = "InForce";
    public static final String POLICY_CANCELLED = "Cancelled";
    public static final String LOB_AUTO = "Auto";
    public static final String LOB_HOME = "Home";
    public static final String FORM_HO3 = "HO-3";

    // Installments
    public static final String INSTALLMENT_SCHEDULED = "Scheduled";
    public static final String INSTALLMENT_PAID = "Paid";
    public static final String INSTALLMENT_PAST_DUE = "PastDue";

    // Cancellation notices
    public static final String NOTICE_ISSUED = "Issued";
    public static final String NOTICE_EXECUTED = "Executed";

    // Claims — specs/claims/auto-accidents/record-car-accident.feature.md
    public static final String CLAIM_OPEN_AWAITING_ADJUSTER = "Open — awaiting adjuster";
    public static final String CLAIM_OPEN_ASSIGNED = "Open — assigned";
    public static final String CLAIM_REPAIR_AUTHORIZED = "Repair authorized";
    public static final String CLAIM_TYPE_AUTO_COLLISION = "AutoCollision";
    public static final String CLAIM_TYPE_HOME_WATER = "HomeWater";
    public static final String CHANNEL_ONLINE = "Online";
    public static final String CHANNEL_HOTLINE = "Hotline";

    // specs/claims/home-claims/record-water-damage-claim.feature.md
    public static final String COVERAGE_SUDDEN_ACCIDENTAL_WATER = "sudden and accidental water damage";

    // specs/claims/auto-accidents/assign-adjuster.feature.md — severity routes
    public static final String ROUTE_FAST_TRACK_DESK = "fast-track desk adjuster";
    public static final String ROUTE_FIELD = "field adjuster";
    public static final String ROUTE_SENIOR_FIELD = "senior field adjuster";

    // Billing plans — specs/billing/collect-premium.feature.md
    public static final String PLAN_PAID_IN_FULL = "Paid in full";
    public static final String PLAN_TWO_PAY = "Two-pay";
    public static final String PLAN_MONTHLY_AUTOPAY = "Monthly autopay";

    // Refunds — specs/billing/refund-unearned-premium.feature.md
    public static final String REFUND_METHOD_ORIGINAL = "original payment method";

    // Endorsements
    public static final String ENDORSEMENT_ADD_VEHICLE = "AddVehicle";

    // Renewal offers
    public static final String OFFER_OFFERED = "Offered";

    // ─── Underwriting ───

    // Submissions — specs/underwriting/eligibility/screen-applicant-eligibility.feature.md
    public static final String SUBMISSION_SCREENING = "Screening";
    public static final String SUBMISSION_ELIGIBLE = "Eligible";
    public static final String SUBMISSION_REFERRED = "Referred";
    public static final String SUBMISSION_DECLINED = "Declined";
    public static final String SUBMISSION_HELD = "Held";

    public static final String SCREENING_RULE_SET_VERSION = "2026.1";
    public static final String RULE_VALID_LICENSE = "Valid license";
    public static final String RULE_LOSS_HISTORY = "Loss history";
    public static final String RULE_INSURANCE_SCORE = "Insurance score";
    public static final String RULE_PRIOR_CANCELLATION = "Prior cancellation";
    public static final String RULE_RESIDENCY = "Residency in state";
    public static final String OUTCOME_DECLINE = "decline";
    public static final String OUTCOME_REFER = "refer";
    public static final String DECLINE_NO_LICENSE = "no valid driver license";

    // specs/underwriting/eligibility/check-prior-loss-history.feature.md
    public static final String REPORT_CLUE_PROPERTY = "CLUE property";
    public static final String PERIL_WATER = "Water";
    public static final String DECLINE_WATER_FREQUENCY = "water loss frequency";
    public static final String EXCLUDED_WEATHER_ONLY = "weather-only catastrophe code";

    // specs/underwriting/eligibility/detect-prior-cancellation.feature.md
    public static final String PRIOR_INSURED_REQUESTED = "insured-requested";
    public static final String PRIOR_NON_PAYMENT = "non-payment";
    public static final String PRIOR_MISREPRESENTATION = "misrepresentation";
    public static final String TIER_NO_PRIOR_INSURANCE = "no-prior-insurance";
    public static final int LAPSE_THRESHOLD_DAYS = 30;

    // specs/underwriting/eligibility/verify-insurance-score.feature.md
    public static final String TIER_NEUTRAL = "C";
    public static final int ADVERSE_ACTION_NOTICE_DAYS = 30;
    public static final int ADVERSE_ACTION_REASON_COUNT = 4;

    // specs/underwriting/referrals/refer-to-underwriter.feature.md
    public static final String REFERRAL_REFERRED = "Referred";
    public static final String REFERRAL_ASSIGNED = "Assigned";
    public static final String REFERRAL_AWAITING_AGENT = "AwaitingAgent";
    public static final String REFERRAL_DECIDED = "Decided";
    public static final String REFERRAL_ESCALATED = "Escalated";
    public static final String REFERRAL_TYPE_STANDARD = "Standard personal";
    public static final String REFERRAL_TYPE_OVER_LIMIT = "Over-limit authority";
    public static final String QUEUE_OVERFLOW = "overflow";
    public static final int OVERFLOW_AFTER_HOURS = 4;

    // specs/underwriting/referrals/apply-referral-sla.feature.md
    public static final String SLA_OPEN = "Open";
    public static final String SLA_MET = "Met";
    public static final String SLA_BREACHED = "Breached";

    // specs/underwriting/referrals/record-underwriter-decision.feature.md
    public static final String DECISION_ACCEPT = "Accept";
    public static final String DECISION_ACCEPT_WITH_CONDITIONS = "AcceptWithConditions";
    public static final String DECISION_COUNTER_OFFER = "CounterOffer";
    public static final String DECISION_DECLINE = "Decline";
    public static final String SUBMISSION_BINDABLE = "Bindable";
    public static final int COUNTER_OFFER_COMPARISON_DAYS = 5;

    // specs/underwriting/referrals/escalate-authority-limit.feature.md
    public static final String ESCALATION_LIMIT = "Limit";
    public static final String ESCALATION_CREDIT = "DiscretionaryCredit";
    public static final String ROLE_UNDERWRITER = "underwriter";
    public static final String ROLE_REGIONAL_MANAGER = "regional underwriting manager";
    public static final String ESCALATION_OPEN = "Open";

    // specs/underwriting/appetite/define-underwriting-appetite.feature.md
    public static final String APPETITE_DRAFT = "Draft";
    public static final String APPETITE_PUBLISHED = "Published";
    public static final String APPETITE_IN = "InAppetite";
    public static final String APPETITE_REFER = "Refer";
    public static final String APPETITE_DECLINE = "Decline";
    public static final String DECLINE_OUTSIDE_APPETITE = "outside published appetite";

    // specs/underwriting/appetite/restrict-by-territory.feature.md
    public static final String TERRITORY_CLOSED = "Closed";
    public static final String TERRITORY_OPEN = "Open";
    public static final String DECLINE_TERRITORY_CLOSED = "territory closed to new business";

    // specs/underwriting/appetite/apply-moratorium.feature.md
    public static final String MORATORIUM_OPEN = "Open";
    public static final String MORATORIUM_LIFTED = "Lifted";
    public static final String PERIL_NAMED_STORM = "NamedStorm";
    public static final String PERIL_WILDFIRE = "Wildfire";
    public static final String AREA_COUNTY = "County";
    public static final String AREA_ZIP = "Zip";
    public static final String REQUEST_QUEUED = "Queued";
    public static final String REQUEST_REPROCESSED = "Reprocessed";
    public static final String REQUEST_COVERAGE_INCREASE = "CoverageIncrease";
    public static final int MORATORIUM_LIFT_HOURS = 72;

    // specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
    public static final String ZONE_PERIL_WIND = "Wind";
    public static final String ZONE_PERIL_EARTHQUAKE = "Earthquake";
    public static final String CAPACITY_OPEN = "open";
    public static final String CAPACITY_RESTRICTED = "restricted";
    public static final String CAPACITY_CLOSED = "closed";
    public static final String APPROVAL_NONE = "none";
    public static final String APPROVAL_UNDERWRITER = "underwriter";
    public static final String APPROVAL_CHIEF_UNDERWRITER = "chief underwriter";
    public static final String DECLINE_CAT_CAPACITY = "catastrophe capacity";

    // specs/underwriting/inspections/order-property-inspection.feature.md
    public static final String INSPECTION_ORDERED = "Ordered";
    public static final String INSPECTION_COMPLETED = "Completed";
    public static final String INSPECTION_NO_ACCESS = "NoAccess";
    public static final String INSPECTION_WAIVED = "Waived";
    public static final String INSPECTION_TYPE_NONE = "none";
    public static final String INSPECTION_TYPE_EXTERIOR = "exterior only";
    public static final String INSPECTION_TYPE_INTERIOR = "interior + exterior";
    public static final String INSPECTION_TYPE_ENGINEER = "engineer walkthrough";
    public static final int VACANCY_DAYS_THRESHOLD = 60;
    public static final int NO_ACCESS_ATTEMPTS = 3;
    public static final int FINAL_ACCESS_DEADLINE_DAYS = 10;
    public static final String NON_RENEWAL_REFUSED_INSPECTION =
            "refusal to permit inspection";

    // specs/underwriting/inspections/order-loss-control-survey.feature.md
    public static final int SURVEY_DUE_DAYS = 45;
    public static final int SURVEY_WAIVER_MONTHS = 24;
    public static final int COASTAL_DISTANCE_FEET = 1500;
    public static final java.math.BigDecimal HIGH_VALUE_DWELLING =
            new java.math.BigDecimal("2000000.00");

    // specs/underwriting/inspections/review-inspection-findings.feature.md
    public static final String DISPOSITION_NO_ACTION = "no action required";
    public static final String DISPOSITION_LIMIT_INCREASE = "LimitIncrease";
    public static final String DISPOSITION_EXCLUSION = "Exclusion";
    public static final String DISPOSITION_NON_RENEWAL = "NonRenewal";
    public static final java.math.BigDecimal RCV_VARIANCE_TOLERANCE_PCT =
            new java.math.BigDecimal("10.00");
    public static final int COVERAGE_CHANGE_NOTICE_DAYS = 45;
    public static final String EXCLUSION_TRAMPOLINE = "trampoline liability exclusion";
    public static final String NON_RENEWAL_HAZARD = "uncorrected hazardous condition";
    public static final int HAZARD_CURE_DAYS = 60;

    // specs/underwriting/inspections/track-inspection-recommendations.feature.md
    public static final String SEVERITY_CRITICAL = "Critical";
    public static final String SEVERITY_ADVISORY = "Advisory";
    public static final String RECOMMENDATION_OPEN = "Open";
    public static final String RECOMMENDATION_COMPLETE = "Complete";
    public static final String RECOMMENDATION_DECLINED = "Declined";
    public static final int CRITICAL_CURE_DAYS = 30;
    public static final int ADVISORY_CURE_DAYS = 30;

    // ─── Distribution ───

    // specs/distribution/agency-management/onboard-agency.feature.md
    public static final String AGENCY_PENDING_APPOINTMENT = "PendingAppointment";
    public static final String AGENCY_APPOINTED = "Appointed";
    public static final String AGENCY_DECLINED = "Declined";
    public static final String AGENCY_TERMINATED = "Terminated";
    public static final String DECLINE_ADVERSE_REGULATORY = "adverse regulatory history";
    public static final int AGENCY_REVIEW_BUSINESS_DAYS = 5;
    public static final String TIER_BRONZE = "Bronze";
    public static final String TIER_SILVER = "Silver";
    public static final String TIER_GOLD = "Gold";

    // specs/distribution/agency-management/manage-agency-appointment.feature.md
    public static final String APPOINTMENT_PENDING = "Pending";
    public static final String APPOINTMENT_ACTIVE = "Active";
    public static final String APPOINTMENT_LAPSED = "Lapsed";
    public static final String APPOINTMENT_PENDING_COUNTERSIGNATURE = "PendingCountersignature";
    public static final String LINE_PERSONAL_AUTO = "Personal auto";
    public static final String LINE_HOMEOWNERS = "Homeowners";
    public static final String LINE_RENTERS = "Renters";
    public static final String LINE_UMBRELLA = "Umbrella";
    public static final String LINE_COMMERCIAL_GL = "Commercial general liability";
    /** Kentucky requires a resident countersignature for commercial lines. */
    public static final String COUNTERSIGNATURE_STATE = "KY";

    // specs/distribution/agency-management/set-commission-schedule.feature.md
    public static final String TXN_NEW_BUSINESS = "NewBusiness";
    public static final String TXN_RENEWAL = "Renewal";
    public static final String TXN_ENDORSEMENT = "Endorsement";
    public static final String TXN_CHARGEBACK = "Chargeback";
    public static final String TXN_CONTINGENT = "Contingent";
    public static final String CANCELLATION_FLAT = "flat";
    public static final String CANCELLATION_SHORT_RATE = "short-rate";
    public static final java.math.BigDecimal CONTINGENT_LOSS_RATIO_CEILING =
            new java.math.BigDecimal("60.00");
    public static final java.math.BigDecimal CONTINGENT_RATE_PCT =
            new java.math.BigDecimal("3.00");
    public static final int CONTINGENT_DEVELOPMENT_DAYS = 90;

    // specs/distribution/agency-management/terminate-agency-relationship.feature.md
    public static final String ROLL_SCHEDULED = "Scheduled";
    public static final String ROLL_COMPLETED = "Completed";
    public static final String ROLL_OVERRIDDEN = "Overridden";

    // specs/distribution/producer-licensing/verify-producer-license.feature.md
    public static final String PRODUCER_ACTIVE = "Active";
    public static final String PRODUCER_DEPARTED = "Departed";
    public static final String PRODUCER_SUSPENDED = "Suspended";
    public static final String LICENSE_ACTIVE = "Active";
    public static final String LICENSE_EXPIRED = "Expired";
    public static final String LICENSE_SUSPENDED = "Suspended";
    public static final String LICENSE_REINSTATED = "Reinstated";
    public static final String LICENSE_CLASS_PC = "property and casualty";
    public static final String LICENSE_CLASS_PERSONAL = "personal lines";
    public static final String REGISTRY_NATIONAL = "national registry";
    public static final int NAME_MISMATCH_GRACE_DAYS = 30;

    // specs/distribution/producer-licensing/track-license-renewal.feature.md
    public static final java.util.List<Integer> REMINDER_DAYS_BEFORE =
            java.util.List.of(90, 60, 30, 7);
    public static final int REMINDER_COPY_PRINCIPAL_DAYS = 7;
    public static final int REINSTATEMENT_WINDOW_MONTHS = 12;

    // specs/distribution/producer-licensing/block-unlicensed-production.feature.md
    public static final String ACTION_BIND = "Bind";
    public static final String ACTION_QUOTE = "Quote";
    public static final String BLOCK_NO_ACTIVE_LICENSE = "no active license in the state of the risk";
    public static final String WITHHELD_PENDING_COMPLIANCE = "written without authority — pending compliance review";
    public static final int OVERRIDE_HOURS = 72;

    // specs/distribution/agent-portal/manage-portal-user-access.feature.md
    public static final String PORTAL_INVITED = "Invited";
    public static final String PORTAL_ACTIVE = "Active";
    public static final String PORTAL_SUSPENDED = "Suspended";
    public static final String PORTAL_DISABLED = "Disabled";
    public static final String PORTAL_DEACTIVATED = "Deactivated";
    public static final String ROLE_ADMINISTRATOR = "Administrator";
    public static final String ROLE_PRODUCER = "Producer";
    public static final String ROLE_SERVICE = "Service";
    public static final int INVITATION_VALID_DAYS = 7;
    public static final int DORMANT_ACCOUNT_DAYS = 90;
    public static final String LAST_ADMIN_MESSAGE =
            "an agency must retain at least one administrator";

    // specs/distribution/agent-portal/start-quote-in-portal.feature.md
    public static final String PORTAL_QUOTE_SAVED = "Saved";
    public static final String PORTAL_QUOTE_REFUSED = "Refused";
    public static final String PORTAL_QUOTE_SUBJECT_TO_INSPECTION = "SubjectToInspection";
    public static final String SUBJECT_TO_INSPECTION = "subject to inspection";
    public static final int ROOF_AGE_INSPECTION_YEARS = 20;
    public static final String FOUR_POINT_INSPECTION = "four-point inspection";

    // specs/distribution/agent-portal/hand-off-quote-to-underwriter.feature.md
    public static final String PORTAL_REFERRAL_REFERRED = "Referred";
    public static final String PORTAL_REFERRAL_AWAITING_INFO = "AwaitingInformation";
    public static final String PORTAL_REFERRAL_ESCALATED = "Escalated";
    public static final String PORTAL_REFERRAL_DECLINED = "Declined";
    public static final String REFERRAL_EXCEEDS_BINDING_AUTHORITY =
            "exceeds producer binding authority";
    public static final String ESCALATION_UNDERWRITING_MANAGER = "underwriting manager";
    public static final String TIER_STANDARD = "Standard";
    public static final String TIER_COMPLEX = "Complex";
    public static final String TIER_LARGE_ACCOUNT = "Large account";
    public static final String SURPLUS_LINES_ADVICE =
            "the risk may be placed through surplus lines";

    // specs/distribution/embedded/integrate-partner-api.feature.md
    public static final String PARTNER_SANDBOX = "Sandbox";
    public static final String PARTNER_CERTIFIED = "Certified";
    public static final String PARTNER_SUSPENDED = "Suspended";
    public static final String CREDENTIAL_ACTIVE = "Active";
    public static final String CREDENTIAL_RETIRING = "Retiring";
    public static final String CREDENTIAL_RETIRED = "Retired";
    public static final int CREDENTIAL_OVERLAP_DAYS = 14;
    public static final int PARTNER_MONITORING_DAYS = 30;
    public static final int SUSTAINED_THROTTLE_MINUTES = 15;
    public static final int API_VERSION_RETIREMENT_MONTHS = 12;
    public static final String API_VERSION_ACTIVE = "Active";
    public static final String API_VERSION_DEPRECATED = "Deprecated";

    // specs/distribution/embedded/offer-embedded-quote.feature.md
    public static final String OFFER_OPEN = "Offered";
    public static final String OFFER_ACCEPTED = "Accepted";
    public static final String OFFER_EXPIRED = "Expired";
    public static final String OFFER_NOT_TAKEN = "NotTaken";
    public static final int EMBEDDED_TOKEN_HOURS = 24;
    public static final java.math.BigDecimal RENTERS_PERSONAL_PROPERTY =
            new java.math.BigDecimal("15000.00");
    public static final java.math.BigDecimal RENTERS_LIABILITY =
            new java.math.BigDecimal("100000.00");

    // specs/distribution/embedded/attribute-embedded-sale.feature.md
    public static final String ATTRIBUTION_ATTRIBUTED = "Attributed";
    public static final String ATTRIBUTION_REVERSED = "Reversed";
    public static final String ATTRIBUTION_REATTRIBUTED = "Reattributed";
    public static final int ATTRIBUTION_LOOKBACK_DAYS = 30;
}
