package com.acme.insurance.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.acme.insurance.domain.Rates;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;

import cds.gen.insurance.Installments;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/billing/collect-premium.feature.md
 * "Unpaid balance triggers a cancellation notice" is [proposed]; the notice
 * artifact exists minimally with an explicit issue action (see
 * CancelAutoPolicyTest), but the nightly issuance stays absent.
 */
@DisplayName("Collect Premium — specs/billing/collect-premium.feature.md")
class CollectPremiumTest extends ScenarioTestBase {

    @Autowired
    BillingManager billing;
    @Autowired
    Rates rates;

    @Test
    @DisplayName("Monthly installment plan @v1")
    void monthlyInstallmentPlan() {
        // Given a bound policy with a $600 six-month premium, billed monthly
        List<Installments> schedule = db(() -> billing.buildInstallments(
                new BigDecimal("600.00"), rates.billingPlan("Monthly autopay"), 6, BASE_DATE));

        // The premium is billed in 6 monthly installments
        assertThat(schedule).hasSize(6);
        for (int i = 0; i < 6; i++) {
            Installments inst = schedule.get(i);
            assertThat(inst.getDueDate()).isEqualTo(BASE_DATE.plusMonths(i));
            assertThat(inst.getPremiumAmount()).isEqualByComparingTo("100.00");
            // Each installment carries the plan's service fee
            assertThat(inst.getServiceFee()).isEqualByComparingTo("5.00");
            assertThat(inst.getTotalAmount()).isEqualByComparingTo("105.00");
        }

        // Plan table: Paid in full → 1 payment, $0 fee; Two-pay → 2 payments, $3 fee
        List<Installments> paidInFull = db(() -> billing.buildInstallments(
                new BigDecimal("600.00"), rates.billingPlan("Paid in full"), 6, BASE_DATE));
        assertThat(paidInFull).hasSize(1);
        assertThat(paidInFull.get(0).getServiceFee()).isEqualByComparingTo("0.00");
        assertThat(paidInFull.get(0).getTotalAmount()).isEqualByComparingTo("600.00");

        List<Installments> twoPay = db(() -> billing.buildInstallments(
                new BigDecimal("600.00"), rates.billingPlan("Two-pay"), 6, BASE_DATE));
        assertThat(twoPay).hasSize(2);
        assertThat(twoPay.get(0).getTotalAmount()).isEqualByComparingTo("303.00");
        assertThat(twoPay.get(1).getDueDate()).isEqualTo(BASE_DATE.plusMonths(3));

        // Money convention: $517 over 6 payments allocates remainder cents largest-first
        List<Installments> uneven = db(() -> billing.buildInstallments(
                new BigDecimal("517.00"), rates.billingPlan("Monthly autopay"), 6, BASE_DATE));
        assertThat(uneven).extracting(Installments::getPremiumAmount)
                .containsExactly(new BigDecimal("86.17"), new BigDecimal("86.17"),
                        new BigDecimal("86.17"), new BigDecimal("86.17"),
                        new BigDecimal("86.16"), new BigDecimal("86.16"));
        BigDecimal premiumSum = uneven.stream().map(Installments::getPremiumAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(premiumSum).isEqualByComparingTo("517.00");
    }

    @Test
    @DisplayName("Failed autopay starts the grace period @v1")
    void failedAutopayStartsTheGracePeriod() {
        Policies policy = insertMonthlyAutopayPolicy(new BigDecimal("600.00"), BASE_DATE, null);

        // Given an installment due today
        LocalDate dueDate = BASE_DATE.plusMonths(1);
        clock.setBusinessDate(dueDate);

        // When the autopay charge is declined
        gateway.declineChargesFor(policy.getPaymentMethodToken());
        dbRun(() -> billing.runAutopay(dueDate));

        Policies reloaded = reloadPolicyTx(policy.getId());
        Installments declined = reloaded.getInstallments().stream()
                .filter(i -> dueDate.equals(i.getDueDate()))
                .findFirst().orElseThrow();

        // Then the charge is retried in 3 days
        assertThat(declined.getStatus()).isEqualTo(Statuses.INSTALLMENT_PAST_DUE);
        assertThat(declined.getRetryOn()).isEqualTo(dueDate.plusDays(3));
        // And a 10-day grace period starts
        assertThat(declined.getGraceEndsOn()).isEqualTo(dueDate.plusDays(10));

        // And the insured is notified by email and text
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
            assertThat(mail.subject()).contains("declined");
        });
        assertThat(texts.sent()).anySatisfy(sms -> {
            assertThat(sms.to()).isEqualTo("+1-555-0100");
            assertThat(sms.body()).contains("declined");
        });

        // The retry actually runs 3 days later and can collect the payment
        gateway.approveChargesFor(policy.getPaymentMethodToken());
        LocalDate retryDay = dueDate.plusDays(3);
        clock.setBusinessDate(retryDay);
        dbRun(() -> billing.runAutopay(retryDay));

        Installments afterRetry = reloadPolicyTx(policy.getId()).getInstallments().stream()
                .filter(i -> dueDate.equals(i.getDueDate()))
                .findFirst().orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(Statuses.INSTALLMENT_PAID);
        assertThat(afterRetry.getPaidOn()).isEqualTo(retryDay);
        assertThat(gateway.charges()).hasSize(2);
    }
}
