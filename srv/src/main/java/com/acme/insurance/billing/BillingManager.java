package com.acme.insurance.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.InstallmentAllocator;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.acme.insurance.ports.PaymentGatewayPort;
import com.acme.insurance.ports.PaymentResult;
import com.acme.insurance.ports.TextMessage;
import com.acme.insurance.ports.TextMessagePort;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.BillingPlans;
import cds.gen.insurance.Installments;
import cds.gen.insurance.Installments_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * Spec: specs/billing/collect-premium.feature.md
 */
@Component
public class BillingManager {

    // "Failed autopay starts the grace period"
    private static final int RETRY_AFTER_DAYS = 3;
    private static final int GRACE_PERIOD_DAYS = 10;

    private final PersistenceService persistence;
    private final PaymentGatewayPort paymentGateway;
    private final EmailPort email;
    private final TextMessagePort text;

    public BillingManager(PersistenceService persistence, PaymentGatewayPort paymentGateway,
            EmailPort email, TextMessagePort text) {
        this.persistence = persistence;
        this.paymentGateway = paymentGateway;
        this.email = email;
        this.text = text;
    }

    /**
     * "Monthly installment plan": the premium is billed in as many installments
     * as the plan defines, each carrying the plan's service fee. Premium cents
     * are allocated largest-first so the schedule sums exactly to premium plus
     * fees (ARCHITECTURE.md "Money").
     */
    public List<Installments> buildInstallments(BigDecimal premium, BillingPlans plan, int termMonths,
            LocalDate termStart) {
        int payments = plan.getPayments();
        int monthsBetween = termMonths / payments;
        BigDecimal fee = plan.getServiceFeePerPayment();
        List<BigDecimal> shares = InstallmentAllocator.allocate(premium, payments);

        List<Installments> schedule = new ArrayList<>(payments);
        for (int i = 0; i < payments; i++) {
            Installments installment = Installments.create();
            installment.setId(UUID.randomUUID().toString());
            installment.setSeq(i + 1);
            installment.setDueDate(termStart.plusMonths((long) i * monthsBetween));
            installment.setPremiumAmount(shares.get(i));
            installment.setServiceFee(fee);
            installment.setTotalAmount(shares.get(i).add(fee));
            installment.setStatus(Statuses.INSTALLMENT_SCHEDULED);
            schedule.add(installment);
        }
        return schedule;
    }

    /**
     * Nightly autopay run: charges installments due today on 'Monthly autopay'
     * policies plus any declined installments whose 3-day retry is due today.
     *
     * "Failed autopay starts the grace period": on a decline the charge is
     * retried in 3 days, a 10-day grace period starts, and the insured is
     * notified by email and text.
     */
    public void runAutopay(LocalDate today) {
        List<Installments> dueToday = persistence.run(Select.from(Installments_.class)
                .where(i -> i.status().eq(Statuses.INSTALLMENT_SCHEDULED)
                        .and(i.dueDate().eq(today))
                        .and(i.policy().billingPlan().eq(Statuses.PLAN_MONTHLY_AUTOPAY))
                        .and(i.policy().status().eq(Statuses.POLICY_IN_FORCE))))
                .listOf(Installments.class);

        List<Installments> retriesDue = persistence.run(Select.from(Installments_.class)
                .where(i -> i.status().eq(Statuses.INSTALLMENT_PAST_DUE)
                        .and(i.retryOn().eq(today))
                        .and(i.policy().status().eq(Statuses.POLICY_IN_FORCE))))
                .listOf(Installments.class);

        List<Installments> toCharge = new ArrayList<>(dueToday);
        toCharge.addAll(retriesDue);
        for (Installments installment : toCharge) {
            chargeInstallment(installment, today);
        }
    }

    private void chargeInstallment(Installments installment, LocalDate today) {
        Policies policy = persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(installment.getPolicyId())))
                .single(Policies.class);

        PaymentResult result = paymentGateway.charge(policy.getPaymentMethodToken(),
                installment.getTotalAmount());

        Map<String, Object> data = new HashMap<>();
        if (result.approved()) {
            data.put(Installments.STATUS, Statuses.INSTALLMENT_PAID);
            data.put(Installments.PAID_ON, today);
            data.put(Installments.RETRY_ON, null);
        } else {
            LocalDate retryOn = today.plusDays(RETRY_AFTER_DAYS);
            data.put(Installments.STATUS, Statuses.INSTALLMENT_PAST_DUE);
            data.put(Installments.RETRY_ON, retryOn);
            LocalDate graceEndsOn = installment.getGraceEndsOn();
            if (graceEndsOn == null) {
                // The grace period starts with the first decline of this installment
                graceEndsOn = installment.getDueDate().plusDays(GRACE_PERIOD_DAYS);
                data.put(Installments.GRACE_ENDS_ON, graceEndsOn);
            }
            notifyDecline(policy, installment, retryOn, graceEndsOn);
        }
        persistence.run(Update.entity(Installments_.class)
                .where(i -> i.ID().eq(installment.getId()))
                .data(data));
    }

    private void notifyDecline(Policies policy, Installments installment, LocalDate retryOn,
            LocalDate graceEndsOn) {
        String amount = "$" + installment.getTotalAmount().toPlainString();
        String bodyText = "Your automatic payment of " + amount + " for policy "
                + policy.getPolicyNumber() + " was declined. We will retry the charge on " + retryOn
                + ". A 10-day grace period is in effect until " + graceEndsOn
                + "; please update your payment method to keep your coverage.";
        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Payment declined — policy " + policy.getPolicyNumber(), bodyText));
        text.send(new TextMessage(policy.getNamedInsuredPhone(), bodyText));
    }
}
