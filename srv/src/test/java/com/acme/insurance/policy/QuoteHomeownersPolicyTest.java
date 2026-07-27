package com.acme.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.HomeQuoteOptions;
import cds.gen.insurance.HomeQuotes;
import cds.gen.insurance.HomeQuotes_;

/**
 * Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
 * "Flood damage is excluded" and "Old roof requires inspection" are [proposed]
 * and intentionally absent.
 */
@DisplayName("Quote Homeowners Policy — specs/policy/home-policy/quote-homeowners-policy.feature.md")
class QuoteHomeownersPolicyTest extends ScenarioTestBase {

    @Test
    @DisplayName("Basic homeowners quote @v1")
    void basicHomeownersQuote() throws Exception {
        HomeQuotes at300k = requestQuote(new BigDecimal("300000.00"));
        assertThat(at300k.getOptions())
                .extracting(HomeQuoteOptions::getDeductible, HomeQuoteOptions::getAnnualPremium)
                .containsExactlyInAnyOrder(
                        tuple(new BigDecimal("1000.00"), new BigDecimal("1420.00")),
                        tuple(new BigDecimal("2500.00"), new BigDecimal("1180.00")));

        HomeQuotes at500k = requestQuote(new BigDecimal("500000.00"));
        assertThat(at500k.getOptions())
                .extracting(HomeQuoteOptions::getDeductible, HomeQuoteOptions::getAnnualPremium)
                .containsExactly(
                        tuple(new BigDecimal("1000.00"), new BigDecimal("2050.00")));
    }

    private HomeQuotes requestQuote(BigDecimal dwellingCoverage) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "address", "42 Elm Street, Springfield",
                "yearBuilt", 1998,
                "constructionType", "frame",
                "ownerName", "Casey Homeowner",
                "ownerEmail", "casey@example.com",
                "dwellingCoverage", dwellingCoverage));

        String response = mvc.perform(post("/odata/v4/quotes/requestHomeQuote")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String quoteId = readTree(response).get("ID").asText();
        return db(() -> persistence.run(Select.from(HomeQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(HomeQuotes.class));
    }
}
