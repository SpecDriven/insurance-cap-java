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
 * Spec: specs/claims/auto-accidents/assign-adjuster.feature.md
 * "Injury claims go to a casualty adjuster" is [proposed] and intentionally absent.
 */
@DisplayName("Assign Adjuster — specs/claims/auto-accidents/assign-adjuster.feature.md")
class AssignAdjusterTest extends ScenarioTestBase {

    @Autowired
    FnolManager fnol;

    @Test
    @DisplayName("Assign by estimated damage @v1")
    void assignByEstimatedDamage() throws Exception {
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("689.00"), BASE_DATE, null);

        // under $5,000 → fast-track desk adjuster
        assertRoute(policy, new BigDecimal("4999.99"), "fast-track desk adjuster", false);
        // $5,000 – $25,000 → field adjuster (both boundaries inclusive)
        assertRoute(policy, new BigDecimal("5000.00"), "field adjuster", false);
        assertRoute(policy, new BigDecimal("25000.00"), "field adjuster", false);
        // over $25,000 → senior field adjuster + supervisor review
        assertRoute(policy, new BigDecimal("25000.01"), "senior field adjuster", true);
    }

    private void assertRoute(Policies policy, BigDecimal estimate, String expectedRoute,
            boolean supervisorReview) throws Exception {
        // Given a newly opened collision claim
        Claims claim = db(() -> fnol.reportAutoAccident(policy.getId(), BASE_DATE.minusDays(1),
                "Downtown garage", "Collision while parking", List.of(), Statuses.CHANNEL_ONLINE));

        // When the initial damage estimate is recorded
        String body = json.writeValueAsString(Map.of(
                "claimId", claim.getId(),
                "estimatedDamage", estimate));
        mvc.perform(post("/odata/v4/claims/recordDamageEstimate")
                        .with(httpBasic("adjuster", "adjuster"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Then the claim is routed by severity
        Claims routed = db(() -> persistence.run(Select.from(Claims_.class)
                .where(c -> c.ID().eq(claim.getId())))
                .single(Claims.class));
        assertThat(routed.getEstimatedDamage()).isEqualByComparingTo(estimate);
        assertThat(routed.getAdjusterRoute()).isEqualTo(expectedRoute);
        assertThat(routed.getSupervisorReviewRequired()).isEqualTo(supervisorReview);
    }
}
