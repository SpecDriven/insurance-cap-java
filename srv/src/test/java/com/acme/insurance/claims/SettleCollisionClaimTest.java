package com.acme.insurance.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/claims/auto-accidents/settle-collision-claim.feature.md
 * "Declare a total loss" is [proposed] and intentionally absent.
 */
@DisplayName("Settle Collision Claim — specs/claims/auto-accidents/settle-collision-claim.feature.md")
class SettleCollisionClaimTest extends ScenarioTestBase {

    @Autowired
    FnolManager fnol;

    @Test
    @DisplayName("Approve repair estimate @v1")
    void approveRepairEstimate() throws Exception {
        // Given an open collision claim with a $500 deductible (Full plan)
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("689.00"), BASE_DATE, p -> {
            p.setPlan("Full");
            p.setDeductible(new BigDecimal("500.00"));
        });
        Claims claim = db(() -> fnol.reportAutoAccident(policy.getId(), BASE_DATE.minusDays(1),
                "Oak Ave & 3rd", "Front-end collision", List.of("damage.jpg"), Statuses.CHANNEL_ONLINE));
        assertThat(claim.getDeductible()).isEqualByComparingTo("500.00");

        // And an approved repair-shop estimate of $4,200
        String estimateBody = json.writeValueAsString(Map.of(
                "claimId", claim.getId(),
                "repairShopName", "Main Street Auto Body",
                "amount", new BigDecimal("4200.00")));
        mvc.perform(post("/odata/v4/claims/recordRepairEstimate")
                        .with(httpBasic("adjuster", "adjuster"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(estimateBody))
                .andExpect(status().isOk());

        // When the adjuster authorizes the repair
        String authorizeBody = json.writeValueAsString(Map.of("claimId", claim.getId()));
        mvc.perform(post("/odata/v4/claims/authorizeRepair")
                        .with(httpBasic("adjuster", "adjuster"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody))
                .andExpect(status().isOk());

        // Then $3,700 is paid directly to the repair shop
        assertThat(gateway.payouts()).hasSize(1);
        assertThat(gateway.payouts().get(0).payeeName()).isEqualTo("Main Street Auto Body");
        assertThat(gateway.payouts().get(0).amount()).isEqualByComparingTo("3700.00");

        Claims settled = db(() -> persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claim.getId())))
                .single(Claims.class));
        assertThat(settled.getPaidToShopAmount()).isEqualByComparingTo("3700.00");
        assertThat(settled.getStatus()).isEqualTo(Statuses.CLAIM_REPAIR_AUTHORIZED);

        // And the insured is notified that the $500 deductible is due to the shop
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
            assertThat(mail.body()).contains("500.00");
            assertThat(mail.body()).containsIgnoringCase("deductible is due to the shop");
        });
    }
}
