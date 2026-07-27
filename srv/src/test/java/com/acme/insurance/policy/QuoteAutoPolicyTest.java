package com.acme.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.ScenarioTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.sap.cds.ql.Select;

import cds.gen.insurance.AutoQuoteOptions;
import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.AutoQuotes_;

/**
 * Spec: specs/policy/auto-policy/quote-policy.feature.md
 * Only the [published] scenarios are implemented; "Basic auto quote @v2" and
 * "Quote expires after 30 days" are [proposed] and intentionally absent.
 */
@DisplayName("Quote Auto Policy — specs/policy/auto-policy/quote-policy.feature.md")
class QuoteAutoPolicyTest extends ScenarioTestBase {

    @Test
    @DisplayName("Basic auto quote @v1")
    void basicAutoQuote() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "vehicleMake", "Honda",
                "vehicleModel", "Civic",
                "vehicleYear", 2024,
                "driverName", "Ana Lopez",
                "driverEmail", "ana.lopez@example.com",
                "driverPhone", "+1-555-0101",
                "atFaultAccidents3y", 0,
                "garagingZip", "94105"));

        String response = mvc.perform(post("/odata/v4/quotes/requestAutoQuote")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode quoteJson = readTree(response);
        String quoteId = quoteJson.get("ID").asText();

        AutoQuotes quote = db(() -> persistence.run(Select.from(AutoQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(AutoQuotes.class));

        assertThat(quote.getStatus()).isEqualTo(Statuses.QUOTE_ACTIVE);
        assertThat(quote.getTermMonths()).isEqualTo(6);

        // A six-month premium is quoted for each available plan
        assertThat(quote.getOptions()).hasSize(3);
        assertThat(quote.getOptions())
                .extracting(AutoQuoteOptions::getPlan, AutoQuoteOptions::getBodilyInjuryLimit,
                        AutoQuoteOptions::getCollisionDeductible, AutoQuoteOptions::getPremium)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Liability Only", "50/100",
                                null, new BigDecimal("312.00")),
                        org.assertj.core.groups.Tuple.tuple("Standard", "100/300",
                                new BigDecimal("1000.00"), new BigDecimal("517.00")),
                        org.assertj.core.groups.Tuple.tuple("Full", "250/500",
                                new BigDecimal("500.00"), new BigDecimal("689.00")));
    }

    @Test
    @DisplayName("Decline high-risk driver @v1")
    void declineHighRiskDriver() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "vehicleMake", "Dodge",
                "vehicleModel", "Charger",
                "vehicleYear", 2022,
                "driverName", "Riley Fast",
                "driverEmail", "riley.fast@example.com",
                "driverPhone", "+1-555-0102",
                "atFaultAccidents3y", 3,
                "garagingZip", "94105"));

        String response = mvc.perform(post("/odata/v4/quotes/requestAutoQuote")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String quoteId = readTree(response).get("ID").asText();
        AutoQuotes quote = db(() -> persistence.run(Select.from(AutoQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(AutoQuotes.class));

        // No quote is offered and the decline reason is recorded
        assertThat(quote.getStatus()).isEqualTo(Statuses.QUOTE_DECLINED);
        assertThat(quote.getDeclineReason()).isEqualTo("does not meet underwriting guidelines");
        assertThat(quote.getOptions()).isEmpty();
    }
}
