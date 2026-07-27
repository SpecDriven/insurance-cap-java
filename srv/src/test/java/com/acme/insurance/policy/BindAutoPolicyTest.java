package com.acme.insurance.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.quoting.QuotingManager;
import com.acme.insurance.support.ScenarioTestBase;
import com.sap.cds.ql.Select;

import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * Spec: specs/policy/auto-policy/bind-policy.feature.md
 * "Uninsured motorist rejection form" is [proposed] and intentionally absent.
 */
@DisplayName("Bind Auto Policy — specs/policy/auto-policy/bind-policy.feature.md")
class BindAutoPolicyTest extends ScenarioTestBase {

    @Autowired
    QuotingManager quoting;

    private AutoQuotes activeQuote() {
        return db(() -> quoting.requestAutoQuote("Honda", "Civic", 2024, "Ana Lopez",
                "ana.lopez@example.com", "+1-555-0101", 0, "94105"));
    }

    @Test
    @DisplayName("Bind from an active quote @v1")
    void bindFromAnActiveQuote() throws Exception {
        AutoQuotes quote = activeQuote();

        String body = json.writeValueAsString(Map.of(
                "quoteId", quote.getId(),
                "plan", "Standard",
                "requestedStartDate", BASE_DATE.toString(),
                "billingPlan", "Monthly autopay",
                "paymentMethodToken", "tok-visa-4242"));

        String response = mvc.perform(post("/odata/v4/policies/bindPolicy")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String policyId = readTree(response).get("ID").asText();
        Policies policy = reloadPolicyTx(policyId);

        // A policy is issued with a unique policy number
        assertThat(policy.getPolicyNumber()).startsWith("PA-");
        assertThat(policy.getStatus()).isEqualTo(Statuses.POLICY_IN_FORCE);
        // The effective date is the requested start date
        assertThat(policy.getEffectiveDate()).isEqualTo(BASE_DATE);
        assertThat(policy.getExpirationDate()).isEqualTo(BASE_DATE.plusMonths(6));
        assertThat(policy.getPremium()).isEqualByComparingTo("517.00");

        // The down payment for the first installment has cleared
        assertThat(gateway.charges()).hasSize(1);
        assertThat(gateway.charges().get(0).paymentMethodToken()).isEqualTo("tok-visa-4242");
        assertThat(gateway.charges().get(0).amount()).isEqualByComparingTo("91.17");
        assertThat(policy.getInstallments())
                .filteredOn(i -> Statuses.INSTALLMENT_PAID.equals(i.getStatus()))
                .hasSize(1);

        // ID cards and the declarations page are emailed to the named insured
        assertThat(emails.sent()).anySatisfy(mail -> {
            assertThat(mail.to()).isEqualTo("ana.lopez@example.com");
            assertThat(mail.attachments()).containsExactlyInAnyOrder("id-cards.pdf", "declarations-page.pdf");
        });

        // Unique policy numbers across binds
        AutoQuotes secondQuote = activeQuote();
        String secondBody = json.writeValueAsString(Map.of(
                "quoteId", secondQuote.getId(),
                "plan", "Full",
                "requestedStartDate", BASE_DATE.plusDays(1).toString(),
                "billingPlan", "Paid in full",
                "paymentMethodToken", "tok-visa-4242"));
        String secondResponse = mvc.perform(post("/odata/v4/policies/bindPolicy")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondPolicyNumber = readTree(secondResponse).get("policyNumber").asText();
        assertThat(secondPolicyNumber).isNotEqualTo(policy.getPolicyNumber());
    }

    @Test
    @DisplayName("Effective date may not be backdated @v1")
    void effectiveDateMayNotBeBackdated() throws Exception {
        AutoQuotes quote = activeQuote();
        long policiesBefore = db(() -> persistence.run(Select.from(Policies_.class)).rowCount());

        String body = json.writeValueAsString(Map.of(
                "quoteId", quote.getId(),
                "plan", "Standard",
                "requestedStartDate", BASE_DATE.minusDays(1).toString(),
                "billingPlan", "Paid in full",
                "paymentMethodToken", "tok-visa-4242"));

        String response = mvc.perform(post("/odata/v4/policies/bindPolicy")
                        .with(httpBasic("prospect", "prospect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // Binding is refused with the reason "coverage cannot start in the past"
        assertThat(response).contains("coverage cannot start in the past");
        long policiesAfter = db(() -> persistence.run(Select.from(Policies_.class)).rowCount());
        assertThat(policiesAfter).isEqualTo(policiesBefore);
        assertThat(gateway.charges()).isEmpty();
    }
}
