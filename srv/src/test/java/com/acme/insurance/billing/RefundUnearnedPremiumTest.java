package com.acme.insurance.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.policy.CancellationManager;
import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.Policies;
import cds.gen.insurance.Refunds;
import cds.gen.insurance.Refunds_;

/**
 * Spec: specs/billing/refund-unearned-premium.feature.md
 * "Refund clears within 10 business days" is [proposed] and intentionally absent.
 */
@DisplayName("Refund Unearned Premium — specs/billing/refund-unearned-premium.feature.md")
class RefundUnearnedPremiumTest extends ScenarioTestBase {

    @Autowired
    CancellationManager cancellation;

    @Test
    @DisplayName("Pro-rata refund on cancellation @v1")
    void proRataRefundOnCancellation() {
        // Given a $600 six-month policy, paid in full at inception
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("600.00"), BASE_DATE, null);

        // cancelled exactly 2 months into the term
        LocalDate cancellationDate = BASE_DATE.plusMonths(2);
        clock.setBusinessDate(cancellationDate);

        // When the cancellation is processed
        dbRun(() -> cancellation.requestCancellation(policy.getId(), cancellationDate));

        // Then $400 of unearned premium is refunded to the original payment method
        assertThat(gateway.refunds()).hasSize(1);
        assertThat(gateway.refunds().get(0).paymentMethodToken())
                .isEqualTo(policy.getPaymentMethodToken());
        assertThat(gateway.refunds().get(0).amount()).isEqualByComparingTo("400.00");

        Refunds refundRow = db(() -> persistence.run(Select.from(Refunds_.class)
                .where(r -> r.policy_ID().eq(policy.getId())))
                .single(Refunds.class));
        assertThat(refundRow.getAmount()).isEqualByComparingTo("400.00");
        assertThat(refundRow.getMethod()).isEqualTo(Statuses.REFUND_METHOD_ORIGINAL);

        Policies cancelled = reloadPolicyTx(policy.getId());
        assertThat(cancelled.getStatus()).isEqualTo(Statuses.POLICY_CANCELLED);
        assertThat(cancelled.getCancellationDate()).isEqualTo(cancellationDate);
    }
}
