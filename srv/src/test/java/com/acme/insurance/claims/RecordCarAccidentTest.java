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
import org.springframework.http.MediaType;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.sap.cds.ql.Select;

import cds.gen.insurance.ClaimPhotos;
import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/claims/auto-accidents/record-car-accident.feature.md
 * "Accident on a lapsed policy" is [proposed] and intentionally absent.
 */
@DisplayName("Record Car Accident — specs/claims/auto-accidents/record-car-accident.feature.md")
class RecordCarAccidentTest extends ScenarioTestBase {

    @Test
    @DisplayName("Report accident online @v1")
    void reportAccidentOnline() throws Exception {
        // Given a named insured with an in-force auto policy
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("689.00"), BASE_DATE, null);

        // When they report an accident with the date, location, a description, and photos
        String body = json.writeValueAsString(Map.of(
                "policyId", policy.getId(),
                "dateOfLoss", BASE_DATE.minusDays(1).toString(),
                "lossLocation", "5th & Main, Springfield",
                "description", "Rear-ended at a stop light",
                "photos", List.of("front-bumper.jpg", "rear-bumper.jpg")));

        String response = mvc.perform(post("/odata/v4/claims/reportAccident")
                        .with(httpBasic("insured", "insured"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode claimJson = readTree(response);
        String claimId = claimJson.get("ID").asText();

        Claims claim = db(() -> persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claimId))
                .columns(c -> c._all(), c -> c.photos().expand()))
                .single(Claims.class));

        // Then a claim is opened with a unique claim number
        assertThat(claim.getClaimNumber()).startsWith("CLM-");
        // And the claim status is "Open — awaiting adjuster"
        assertThat(claim.getStatus()).isEqualTo("Open — awaiting adjuster");
        assertThat(claim.getChannel()).isEqualTo(Statuses.CHANNEL_ONLINE);
        assertThat(claim.getDateOfLoss()).isEqualTo(BASE_DATE.minusDays(1));
        assertThat(claim.getPhotos()).extracting(ClaimPhotos::getFileName)
                .containsExactlyInAnyOrder("front-bumper.jpg", "rear-bumper.jpg");

        // And a confirmation email with the claim number is sent
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("jordan.smith@example.com");
            assertThat(mail.subject()).contains(claim.getClaimNumber());
        });
    }

    @Test
    @DisplayName("Report accident through the claims hotline @v1")
    void reportAccidentThroughTheClaimsHotline() throws Exception {
        // Given a caller reaches the 24/7 claims hotline
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("689.00"), BASE_DATE, null);

        // When a live agent records the accident details on the insured's behalf
        String body = json.writeValueAsString(Map.of(
                "policyId", policy.getId(),
                "dateOfLoss", BASE_DATE.minusDays(2).toString(),
                "lossLocation", "I-80 westbound, mile 12",
                "description", "Sideswiped while merging",
                "photos", List.of()));

        String response = mvc.perform(post("/odata/v4/claims/reportAccidentForInsured")
                        .with(httpBasic("agent", "agent"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode claimJson = readTree(response);
        // The agent reads the claim number back to the caller — it is in the response
        String claimNumber = claimJson.get("claimNumber").asText();
        assertThat(claimNumber).startsWith("CLM-");

        String claimId = claimJson.get("ID").asText();
        Claims claim = db(() -> persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claimId)))
                .single(Claims.class));

        // A claim is opened exactly as if it had been reported online
        assertThat(claim.getStatus()).isEqualTo("Open — awaiting adjuster");
        assertThat(claim.getClaimType()).isEqualTo(Statuses.CLAIM_TYPE_AUTO_COLLISION);
        assertThat(claim.getChannel()).isEqualTo(Statuses.CHANNEL_HOTLINE);
        assertThat(claim.getDeductible()).isEqualByComparingTo(policy.getDeductible());
        assertThat(emails.sent()).anySatisfy(mail ->
                assertThat(mail.subject()).contains(claimNumber));
    }
}
