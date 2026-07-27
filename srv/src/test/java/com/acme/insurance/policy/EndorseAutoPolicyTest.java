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
import org.springframework.http.MediaType;

import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.Endorsements;
import cds.gen.insurance.Endorsements_;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/policy/auto-policy/endorse-policy.feature.md
 * "Change garaging address" and "Remove a vehicle" are [proposed] and
 * intentionally absent.
 */
@DisplayName("Endorse Auto Policy — specs/policy/auto-policy/endorse-policy.feature.md")
class EndorseAutoPolicyTest extends ScenarioTestBase {

    @Test
    @DisplayName("Add a vehicle mid-term @v1")
    void addAVehicleMidTerm() throws Exception {
        // Given an in-force auto policy (Standard plan) with 4 months remaining
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("517.00"), BASE_DATE, null);
        LocalDate endorsementDate = BASE_DATE.plusMonths(2);
        clock.setBusinessDate(endorsementDate);

        // When the insured adds a second vehicle
        String body = json.writeValueAsString(Map.of(
                "policyId", policy.getId(),
                "make", "Subaru",
                "model", "Outback",
                "modelYear", 2025));
        mvc.perform(post("/odata/v4/policies/addVehicle")
                        .with(httpBasic("insured", "insured"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Then the additional premium is prorated over the remaining 4 months:
        // Standard rate $517 × 4/6 = $344.67
        Endorsements endorsement = db(() -> persistence.run(Select.from(Endorsements_.class)
                .where(e -> e.policy_ID().eq(policy.getId())))
                .single(Endorsements.class));
        assertThat(endorsement.getAdditionalPremium()).isEqualByComparingTo("344.67");
        assertThat(endorsement.getEndorsementDate()).isEqualTo(endorsementDate);

        Policies endorsed = reloadPolicyTx(policy.getId());
        assertThat(endorsed.getVehicles()).hasSize(2);
        assertThat(endorsed.getPremium()).isEqualByComparingTo("861.67");

        // And a revised declarations page is issued
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
            assertThat(mail.subject()).containsIgnoringCase("revised declarations");
            assertThat(mail.attachments()).contains("declarations-page.pdf");
        });
    }
}
