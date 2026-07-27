package com.acme.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.acme.insurance.billing.BillingManager;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.CancellationNotices;
import cds.gen.insurance.CancellationNotices_;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/policy/auto-policy/cancel-policy.feature.md
 */
@DisplayName("Cancel Auto Policy — specs/policy/auto-policy/cancel-policy.feature.md")
class CancelAutoPolicyTest extends ScenarioTestBase {

    @Autowired
    BillingManager billing;
    @Autowired
    CancellationManager cancellation;

    @Test
    @DisplayName("Insured requests cancellation @v1")
    void insuredRequestsCancellation() throws Exception {
        // Given an in-force policy ($600, six months, paid in full)
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("600.00"), BASE_DATE, null);

        // When the named insured requests cancellation as of a chosen date
        LocalDate chosenDate = BASE_DATE.plusMonths(1);
        clock.setBusinessDate(chosenDate);
        String body = json.writeValueAsString(Map.of(
                "policyId", policy.getId(),
                "cancellationDate", chosenDate.toString()));
        mvc.perform(post("/odata/v4/policies/requestCancellation")
                        .with(httpBasic("insured", "insured"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Then the policy is cancelled on that date
        Policies cancelled = reloadPolicyTx(policy.getId());
        assertThat(cancelled.getStatus()).isEqualTo(Statuses.POLICY_CANCELLED);
        assertThat(cancelled.getCancellationDate()).isEqualTo(chosenDate);
        assertThat(cancelled.getCancellationReason()).isEqualTo(CancellationManager.REASON_INSURED_REQUEST);

        // And unearned premium is refunded pro rata ($600 − 1/6 earned = $500)
        assertThat(gateway.refunds()).hasSize(1);
        assertThat(gateway.refunds().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(gateway.refunds().get(0).paymentMethodToken())
                .isEqualTo(policy.getPaymentMethodToken());
    }

    @Test
    @DisplayName("Cancellation for non-payment @v1")
    void cancellationForNonPayment() throws Exception {
        // Given a monthly-autopay policy whose second installment fails on its due date
        Policies policy = insertMonthlyAutopayPolicy(new BigDecimal("600.00"), BASE_DATE, null);
        LocalDate dueDate = BASE_DATE.plusMonths(1);
        clock.setBusinessDate(dueDate);
        gateway.declineChargesFor(policy.getPaymentMethodToken());
        dbRun(() -> billing.runAutopay(dueDate));

        // and the 10-day grace period has ended
        LocalDate graceEnd = dueDate.plusDays(10);
        clock.setBusinessDate(graceEnd);
        String body = json.writeValueAsString(Map.of("policyId", policy.getId()));
        String noticeResponse = mvc.perform(post("/odata/v4/policies/issueCancellationNotice")
                        .with(httpBasic("agent", "agent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        LocalDate noticeCancellationDate = LocalDate.parse(
                readTree(noticeResponse).get("cancellationDate").asText());
        assertThat(noticeCancellationDate).isEqualTo(graceEnd.plusDays(14));

        // When the 14-day cancellation notice period elapses without payment
        clock.setBusinessDate(noticeCancellationDate);
        dbRun(() -> cancellation.processDueCancellations(noticeCancellationDate));

        // Then the policy is cancelled effective the date stated on the notice
        Policies cancelled = reloadPolicyTx(policy.getId());
        assertThat(cancelled.getStatus()).isEqualTo(Statuses.POLICY_CANCELLED);
        assertThat(cancelled.getCancellationDate()).isEqualTo(noticeCancellationDate);
        assertThat(cancelled.getCancellationReason()).isEqualTo(CancellationManager.REASON_NON_PAYMENT);

        CancellationNotices notice = db(() -> persistence.run(Select.from(CancellationNotices_.class)
                .where(n -> n.policy_ID().eq(policy.getId())))
                .single(CancellationNotices.class));
        assertThat(notice.getStatus()).isEqualTo(Statuses.NOTICE_EXECUTED);

        // And any unearned premium is refunded — here the earned premium
        // (≈ $185.71 for 1 month 24 days of a $600 six-month term) exceeds the
        // $100 premium paid, so no unearned premium remains and no refund is due
        assertThat(gateway.refunds()).isEmpty();
    }
}
