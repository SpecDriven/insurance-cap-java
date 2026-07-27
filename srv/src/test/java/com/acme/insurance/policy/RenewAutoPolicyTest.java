package com.acme.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.acme.insurance.billing.BillingManager;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;

import cds.gen.insurance.AutoPlanRates;
import cds.gen.insurance.AutoPlanRates_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.RenewalOffers;
import cds.gen.insurance.RenewalOffers_;

/**
 * Spec: specs/policy/auto-policy/renew-policy.feature.md
 * "Non-renewal for claim frequency" is [proposed] and intentionally absent.
 */
@DisplayName("Renew Auto Policy — specs/policy/auto-policy/renew-policy.feature.md")
class RenewAutoPolicyTest extends ScenarioTestBase {

    @Autowired
    RenewalManager renewal;
    @Autowired
    BillingManager billing;

    @Test
    @DisplayName("Automatic renewal offer @v1")
    void automaticRenewalOffer() {
        // Given an in-force policy expiring in 45 days with no unpaid balance
        LocalDate today = BASE_DATE;
        LocalDate effective = today.plusDays(45).minusMonths(6);
        Policies healthy = insertPaidInFullAutoPolicy(new BigDecimal("517.00"), effective, null);
        assertThat(healthy.getExpirationDate()).isEqualTo(today.plusDays(45));

        // And a policy with an unpaid balance expiring the same day (gets no offer)
        Policies delinquent = insertMonthlyAutopayPolicy(new BigDecimal("600.00"), effective, null);
        LocalDate pastDue = effective.plusMonths(1);
        clock.setBusinessDate(pastDue);
        gateway.declineChargesFor(delinquent.getPaymentMethodToken());
        dbRun(() -> billing.runAutopay(pastDue));
        clock.setBusinessDate(today);

        // Current rates moved since the policy was bound: Standard is now $545
        dbRun(() -> persistence.run(Update.entity(AutoPlanRates_.class)
                .where(r -> r.plan().eq("Standard"))
                .data(Map.of(AutoPlanRates.SIX_MONTH_PREMIUM, new BigDecimal("545.00")))));

        try {
            // When the nightly renewal job runs
            dbRun(() -> renewal.generateOffers(today));

            // Then a renewal offer is generated at current rates
            List<RenewalOffers> offers = db(() -> persistence.run(Select.from(RenewalOffers_.class))
                    .listOf(RenewalOffers.class));
            assertThat(offers).hasSize(1);
            RenewalOffers offer = offers.get(0);
            assertThat(offer.getPolicyId()).isEqualTo(healthy.getId());
            assertThat(offer.getPremium()).isEqualByComparingTo("545.00");
            assertThat(offer.getOfferedOn()).isEqualTo(today);
            assertThat(offer.getNewEffectiveDate()).isEqualTo(healthy.getExpirationDate());
            assertThat(offer.getStatus()).isEqualTo(Statuses.OFFER_OFFERED);

            // And the offer is sent to the named insured
            assertThat(emails.sent()).anySatisfy(mail -> {
                assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
                assertThat(mail.subject()).containsIgnoringCase("renewal offer");
                assertThat(mail.body()).contains("545.00");
            });
        } finally {
            dbRun(() -> persistence.run(Update.entity(AutoPlanRates_.class)
                    .where(r -> r.plan().eq("Standard"))
                    .data(Map.of(AutoPlanRates.SIX_MONTH_PREMIUM, new BigDecimal("517.00")))));
        }
    }
}
