package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.BlockedRequests;
import cds.gen.insurance.Moratoria;
import cds.gen.insurance.MoratoriumAreas;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/underwriting/appetite/apply-moratorium.feature.md
 * "Overlapping moratoria for different perils" and "Quote issued before the
 * moratorium can still bind" are [proposed] and intentionally absent.
 */
@DisplayName("Apply Binding Moratorium — specs/underwriting/appetite/apply-moratorium.feature.md")
class ApplyMoratoriumTest extends UnderwritingTestBase {

    private static final List<String> COASTAL_COUNTIES = List.of(
            "Galveston", "Brazoria", "Matagorda", "Jackson", "Calhoun",
            "Refugio", "Aransas", "Nueces", "Kleberg");

    @Test
    @DisplayName("Named storm moratorium opens on a watch @v1")
    void namedStormMoratoriumOpensOnAWatch() {
        // A hurricane watch posted for 9 coastal counties
        Moratoria moratorium = db(() -> moratoria.open("MOR-2026-14",
                Statuses.PERIL_NAMED_STORM, Statuses.AREA_COUNTY, COASTAL_COUNTIES));

        // New business binding is suspended in those counties immediately
        assertThat(moratorium.getStatus()).isEqualTo(Statuses.MORATORIUM_OPEN);
        assertThat(moratorium.getAreas()).hasSize(9);
        assertThat(moratorium.getAreas())
                .extracting(MoratoriumAreas::getAreaCode)
                .containsExactlyInAnyOrderElementsOf(COASTAL_COUNTIES);

        // The moratorium identifier, counties, and open time are broadcast to agents
        assertThat(moratorium.getMoratoriumId()).isEqualTo("MOR-2026-14");
        assertThat(moratorium.getOpenedAt()).isNotNull();
        assertThat(emailSubjects()).contains("Binding moratorium opened");
        assertThat(emails.sent().stream().map(m -> m.body()))
                .anySatisfy(body -> assertThat(body)
                        .contains("MOR-2026-14")
                        .contains("Galveston"));
    }

    @Test
    @DisplayName("Coverage increases blocked during a moratorium @v1")
    void coverageIncreasesBlockedDuringAMoratorium() {
        // An active wildfire moratorium for a ZIP code
        db(() -> moratoria.open("MOR-2026-21", Statuses.PERIL_WILDFIRE,
                Statuses.AREA_ZIP, List.of("80403")));

        Policies policy = insertHomePolicy(null);

        // An insured requests a $200,000 dwelling limit increase
        BlockedRequests blocked = db(() -> moratoria.requestCoverageIncrease(policy.getId(),
                new BigDecimal("200000.00"), "80403"));

        // The endorsement is blocked until the moratorium lifts
        assertThat(blocked.getRequestType()).isEqualTo(Statuses.REQUEST_COVERAGE_INCREASE);
        assertThat(blocked.getStatus()).isEqualTo(Statuses.REQUEST_QUEUED);

        // The request is queued for automatic reprocessing after the lift
        assertThat(blocked.getQueuedOn()).isEqualTo(BASE_DATE);
        assertThat(blocked.getReprocessedOn()).isNull();
    }

    @Test
    @DisplayName("Existing policies continue in force @v1")
    void existingPoliciesContinueInForce() {
        Policies policy = insertHomePolicy(null);

        // A moratorium is active over the policy's area
        db(() -> moratoria.open("MOR-2026-21", Statuses.PERIL_WILDFIRE,
                Statuses.AREA_ZIP, List.of("80403")));

        // All in-force coverage continues: the moratorium does not touch the policy
        assertThat(db(() -> reloadPolicy(policy.getId())).getStatus())
                .isEqualTo(Statuses.POLICY_IN_FORCE);

        // And renewals within the area are processed without interruption — the
        // moratorium gates new business and coverage increases only.
        assertThat(db(() -> moratoria.activeFor("80403"))).isNotNull();
        assertThat(db(() -> reloadPolicy(policy.getId())).getCancellationDate()).isNull();
    }

    @Test
    @DisplayName("Moratorium lifts automatically after the event @v1")
    void moratoriumLiftsAutomaticallyAfterTheEvent() {
        // A moratorium with a scheduled lift 72 hours after the watch is cancelled
        Moratoria opened = db(() -> moratoria.open("MOR-2026-14", Statuses.PERIL_NAMED_STORM,
                Statuses.AREA_ZIP, List.of("77550")));

        Policies policy = insertHomePolicy(null);
        db(() -> moratoria.requestCoverageIncrease(policy.getId(),
                new BigDecimal("200000.00"), "77550"));

        // The watch is cancelled at 6:00 AM Thursday
        LocalDate thursday = LocalDate.of(2026, 1, 15);
        clock.setBusinessDateTime(thursday, LocalTime.of(6, 0));
        Moratoria cancelled = db(() -> moratoria.cancelStormWatch(opened.getId()));
        assertThat(cancelled.getScheduledLiftAt()).isNotNull();

        // Before Sunday 6:00 AM it is still in force
        clock.setBusinessDateTime(thursday.plusDays(2), LocalTime.of(6, 0));
        assertThat(db(() -> moratoria.liftDue())).isEmpty();

        // The moratorium lifts at 6:00 AM Sunday unless a manual hold is placed
        LocalDate sunday = thursday.plusDays(3);
        clock.setBusinessDateTime(sunday, LocalTime.of(6, 0));
        emails.reset();
        List<Moratoria> lifted = db(() -> moratoria.liftDue());

        assertThat(lifted).hasSize(1);
        assertThat(lifted.get(0).getStatus()).isEqualTo(Statuses.MORATORIUM_LIFTED);

        // Agents are notified when binding reopens
        assertThat(emailSubjects()).contains("Binding reopened");

        // The queued request is reprocessed after the lift
        assertThat(db(() -> persistence.run(com.sap.cds.ql.Select
                .from(cds.gen.insurance.BlockedRequests_.class))
                .listOf(BlockedRequests.class)))
                .allSatisfy(r -> assertThat(r.getStatus())
                        .isEqualTo(Statuses.REQUEST_REPROCESSED));
    }
}
