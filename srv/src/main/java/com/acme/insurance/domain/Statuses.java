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
}
