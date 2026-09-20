package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.Policies;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.TerritoryRestrictions;

/**
 * Spec: specs/underwriting/appetite/restrict-by-territory.feature.md
 * "Address falls on a territory boundary" and "Agent writes outside their
 * appointed territory" are [proposed] and intentionally absent.
 */
@DisplayName("Restrict Writing by Territory — specs/underwriting/appetite/restrict-by-territory.feature.md")
class RestrictByTerritoryTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Territory closed to new personal auto business @v1")
    void territoryClosedToNewPersonalAutoBusiness() {
        // Territory 214 closed to new personal auto business for rate inadequacy
        LocalDate closedOn = BASE_DATE.minusMonths(2);
        closeTerritory("214", Statuses.LOB_AUTO, "rate inadequacy", closedOn);

        Submissions submission = newSubmission(null);
        db(() -> {
            Submissions s = eligibility.load(submission.getId());
            s.setTerritoryCode("214");
            s.setLineOfBusiness(Statuses.LOB_AUTO);
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Submissions_.class).entry(s));
            return s;
        });

        Submissions screened = db(() -> territories.screen(submission.getId()));

        // The submission is declined with reason "territory closed to new business"
        assertThat(screened.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);
        assertThat(screened.getDeclineReason()).startsWith(Statuses.DECLINE_TERRITORY_CLOSED);

        // The agent is shown the closure effective date
        assertThat(screened.getDeclineReason()).contains(closedOn.toString());
    }

    @Test
    @DisplayName("Limited writings quota per agency @v1")
    void limitedWritingsQuotaPerAgency() {
        // An agency with a monthly quota of 25 new policies in territory 308,
        // which has already written 25 this month
        insertQuota("AG-100", "308", 25, 25, "2026-01");

        Submissions submission = newHomeSubmission();
        Submissions screened = db(() -> territories.screen(submission.getId()));

        // The submission is held until the quota resets on the first of the month
        assertThat(screened.getStatus()).isEqualTo(Statuses.SUBMISSION_HELD);
        assertThat(screened.getDeclineReason())
                .contains(BASE_DATE.plusMonths(1).withDayOfMonth(1).toString());

        // The agency principal receives a quota notice
        assertThat(emailSubjects()).contains("Territory writing quota reached");
    }

    @Test
    @DisplayName("Renewals continue in a closed territory @v1")
    void renewalsContinueInAClosedTerritory() {
        // An in-force auto policy garaged in a closed territory, expiring in 45 days
        closeTerritory("214", Statuses.LOB_AUTO, "rate inadequacy", BASE_DATE.minusMonths(2));
        LocalDate effective = BASE_DATE.plusDays(45).minusMonths(6);
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("517.00"), effective,
                p -> p.setGaragingZip("214"));

        try {
            // The renewal is processed: the territory gate screens NEW business
            // only, so the offer is generated at the filed rate without interference.
            var offers = db(() -> renewals.generateOffers(BASE_DATE));

            assertThat(offers)
                    .extracting(o -> o.getPolicyId())
                    .contains(policy.getId());
            assertThat(offers.stream()
                    .filter(o -> o.getPolicyId().equals(policy.getId()))
                    .findFirst().orElseThrow().getStatus())
                    .isEqualTo(Statuses.OFFER_OFFERED);

            // The policy renews without appetite interference
            assertThat(db(() -> reloadPolicy(policy.getId())).getStatus())
                    .isEqualTo(Statuses.POLICY_IN_FORCE);
        } finally {
            // The H2 database is shared across the test run and
            // RenewAutoPolicyTest asserts on the whole RenewalOffers table, so
            // remove both the offer and the renewable policy this scenario added.
            dbRun(() -> {
                persistence.run(com.sap.cds.ql.Delete
                        .from(cds.gen.insurance.RenewalOffers_.class)
                        .where(o -> o.policy_ID().eq(policy.getId())));
                persistence.run(com.sap.cds.ql.Delete
                        .from(cds.gen.insurance.Policies_.class)
                        .where(p -> p.ID().eq(policy.getId())));
            });
        }
    }

    @Test
    @DisplayName("Territory reopened after a rate filing is approved @v1")
    void territoryReopenedAfterARateFilingIsApproved() {
        // A closed territory awaiting a 12.4% rate filing
        closeTerritory("214", Statuses.LOB_HOME, "rate inadequacy", BASE_DATE.minusMonths(2));

        // A submission held in that territory
        Submissions held = newSubmission(null);
        db(() -> {
            Submissions s = eligibility.load(held.getId());
            s.setTerritoryCode("214");
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Submissions_.class).entry(s));
            return s;
        });
        db(() -> territories.screen(held.getId()));
        assertThat(db(() -> eligibility.load(held.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_DECLINED);

        // The department of insurance approves the filing effective March 1
        LocalDate march1 = LocalDate.of(2026, 3, 1);
        TerritoryRestrictions reopened =
                db(() -> territories.reopen("214", Statuses.LOB_HOME, march1));

        // The territory reopens for new business on the filing's effective date
        assertThat(reopened.getStatus()).isEqualTo(Statuses.TERRITORY_OPEN);
        assertThat(reopened.getReopenDate()).isEqualTo(march1);

        // Held submissions are released for requoting at the approved rate
        assertThat(db(() -> eligibility.load(held.getId())).getStatus())
                .isEqualTo(Statuses.SUBMISSION_SCREENING);
    }
}
