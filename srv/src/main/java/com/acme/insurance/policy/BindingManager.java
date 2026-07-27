package com.acme.insurance.policy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.billing.BillingManager;
import com.acme.insurance.domain.NumberRanges;
import com.acme.insurance.domain.Rates;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.acme.insurance.ports.PaymentGatewayPort;
import com.acme.insurance.ports.PaymentResult;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoQuoteOptions;
import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.AutoQuotes_;
import cds.gen.insurance.BillingPlans;
import cds.gen.insurance.Installments;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.PolicyVehicles;

/**
 * Spec: specs/policy/auto-policy/bind-policy.feature.md
 */
@Component
public class BindingManager {

    private final PersistenceService persistence;
    private final Rates rates;
    private final BillingManager billing;
    private final NumberRanges numbers;
    private final PaymentGatewayPort paymentGateway;
    private final EmailPort email;
    private final Clock clock;

    public BindingManager(PersistenceService persistence, Rates rates, BillingManager billing,
            NumberRanges numbers, PaymentGatewayPort paymentGateway, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.rates = rates;
        this.billing = billing;
        this.numbers = numbers;
        this.paymentGateway = paymentGateway;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Bind from an active quote": the accepted quote becomes an in-force
     * policy once the down payment for the first installment has cleared; a
     * unique policy number is assigned, the effective date is the requested
     * start date, and ID cards plus the declarations page are emailed to the
     * named insured.
     *
     * "Effective date may not be backdated": binding with a start date earlier
     * than today is refused with the reason "coverage cannot start in the past".
     */
    public Policies bind(String quoteId, String plan, LocalDate requestedStartDate, String billingPlanName,
            String paymentMethodToken) {

        AutoQuotes quote = persistence.run(Select.from(AutoQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .first(AutoQuotes.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Quote not found"));

        if (!Statuses.QUOTE_ACTIVE.equals(quote.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The quote is not active");
        }

        LocalDate today = LocalDate.now(clock);
        if (requestedStartDate == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "A requested start date is required");
        }
        if (requestedStartDate.isBefore(today)) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "coverage cannot start in the past");
        }

        AutoQuoteOptions option = quote.getOptions().stream()
                .filter(o -> o.getPlan().equals(plan))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Plan '" + plan + "' is not offered on this quote"));

        BillingPlans billingPlan = rates.billingPlan(
                billingPlanName == null ? Statuses.PLAN_PAID_IN_FULL : billingPlanName);

        int termMonths = quote.getTermMonths();
        List<Installments> schedule = billing.buildInstallments(option.getPremium(), billingPlan,
                termMonths, requestedStartDate);

        // The down payment for the first installment must clear before issuance
        Installments downPayment = schedule.get(0);
        PaymentResult charge = paymentGateway.charge(paymentMethodToken, downPayment.getTotalAmount());
        if (!charge.approved()) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "The down payment was declined");
        }
        downPayment.setStatus(Statuses.INSTALLMENT_PAID);
        downPayment.setPaidOn(today);

        PolicyVehicles vehicle = PolicyVehicles.create();
        vehicle.setId(UUID.randomUUID().toString());
        vehicle.setMake(quote.getVehicleMake());
        vehicle.setModel(quote.getVehicleModel());
        vehicle.setModelYear(quote.getVehicleYear());
        vehicle.setAddedOn(requestedStartDate);

        Policies policy = Policies.create();
        policy.setId(UUID.randomUUID().toString());
        policy.setPolicyNumber(numbers.nextPolicyNumber());
        policy.setLineOfBusiness(Statuses.LOB_AUTO);
        policy.setStatus(Statuses.POLICY_IN_FORCE);
        policy.setPlan(option.getPlan());
        policy.setBodilyInjuryLimit(option.getBodilyInjuryLimit());
        policy.setDeductible(option.getCollisionDeductible());
        policy.setPremium(option.getPremium());
        policy.setTermMonths(termMonths);
        policy.setEffectiveDate(requestedStartDate);
        policy.setExpirationDate(requestedStartDate.plusMonths(termMonths));
        policy.setNamedInsuredName(quote.getDriverName());
        policy.setNamedInsuredEmail(quote.getDriverEmail());
        policy.setNamedInsuredPhone(quote.getDriverPhone());
        policy.setGaragingZip(quote.getGaragingZip());
        policy.setBillingPlan(billingPlan.getPlan());
        policy.setPaymentMethodToken(paymentMethodToken);
        policy.setQuoteId(quote.getId());
        policy.setVehicles(List.of(vehicle));
        policy.setInstallments(schedule);

        persistence.run(Insert.into(Policies_.class).entry(policy));
        persistence.run(Update.entity(AutoQuotes_.class)
                .where(q -> q.ID().eq(quote.getId()))
                .data(Map.of(AutoQuotes.STATUS, Statuses.QUOTE_BOUND)));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Your auto policy " + policy.getPolicyNumber() + " is in force",
                "Welcome! Policy " + policy.getPolicyNumber() + " is effective "
                        + policy.getEffectiveDate() + ". Your ID cards and declarations page are attached.",
                List.of("id-cards.pdf", "declarations-page.pdf")));

        return readPolicy(policy.getId());
    }

    public Policies readPolicy(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .columns(p -> p._all(), p -> p.vehicles().expand(), p -> p.installments().expand()))
                .single(Policies.class);
    }
}
