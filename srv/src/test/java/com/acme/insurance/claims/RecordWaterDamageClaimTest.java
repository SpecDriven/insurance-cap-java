package com.acme.insurance.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/claims/home-claims/record-water-damage-claim.feature.md
 * "Gradual leak is denied" and "Surface flood is referred to the flood policy"
 * are [proposed] and intentionally absent.
 */
@DisplayName("Record Water Damage Claim — specs/claims/home-claims/record-water-damage-claim.feature.md")
class RecordWaterDamageClaimTest extends ScenarioTestBase {

    @Test
    @DisplayName("Burst pipe is covered @v1")
    void burstPipeIsCovered() throws Exception {
        // Given a homeowner with an in-force HO-3 policy
        Policies policy = insertHomePolicy(null);
        assertThat(policy.getFormType()).isEqualTo("HO-3");

        // When they report that a pipe burst yesterday and flooded the kitchen
        String body = json.writeValueAsString(Map.of(
                "policyId", policy.getId(),
                "dateOfLoss", BASE_DATE.minusDays(1).toString(),
                "description", "A pipe burst yesterday and flooded the kitchen",
                "causeOfLoss", "burst pipe"));

        String response = mvc.perform(post("/odata/v4/claims/reportWaterDamage")
                        .with(httpBasic("insured", "insured"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String claimId = readTree(response).get("ID").asText();
        Claims claim = db(() -> persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claimId)))
                .single(Claims.class));

        // Then a claim is opened as "sudden and accidental water damage"
        assertThat(claim.getClaimNumber()).startsWith("CLM-");
        assertThat(claim.getCoverageClassification()).isEqualTo("sudden and accidental water damage");
        assertThat(claim.getStatus()).isEqualTo("Open — awaiting adjuster");

        // And an emergency mitigation vendor is offered within 24 hours
        assertThat(claim.getMitigationVendorName()).isNotBlank();
        assertThat(claim.getMitigationVendorOfferedAt()).isNotNull();
        long hoursUntilOffer = ChronoUnit.HOURS.between(clock.instant(),
                claim.getMitigationVendorOfferedAt());
        assertThat(Duration.ofHours(Math.abs(hoursUntilOffer))).isLessThan(Duration.ofHours(24));
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
            assertThat(mail.body()).contains(claim.getMitigationVendorName());
            assertThat(mail.body()).contains("within 24 hours");
        });
    }
}
