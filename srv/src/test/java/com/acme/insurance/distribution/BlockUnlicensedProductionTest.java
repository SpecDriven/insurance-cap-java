package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.CommissionEntries;
import cds.gen.insurance.ComplianceOverrides;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Producers;
import cds.gen.insurance.ProductionBlocks;

/**
 * Spec: specs/distribution/producer-licensing/block-unlicensed-production.feature.md
 * "Quoting is permitted while binding is blocked" and "Repeated blocked
 * attempts trigger an agency review" are [proposed] and intentionally absent.
 */
@DisplayName("Block Unlicensed Production — specs/distribution/producer-licensing/block-unlicensed-production.feature.md")
class BlockUnlicensedProductionTest extends DistributionTestBase {

    private static final LocalDate JAN_1_2026 = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("Unlicensed producer cannot bind @v1")
    void unlicensedProducerCannotBind() {
        // A producer with no active license in the state of the risk
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newUnlicensedProducer(agency.getId(), "7700101");

        // The producer attempts to bind a quote
        assertThatThrownBy(() -> db(() -> blocks.attemptBind(producer.getId(), "OH", null)))
                // The bind is refused
                .hasMessageContaining("Bind refused")
                .hasMessageContaining(Statuses.BLOCK_NO_ACTIVE_LICENSE);

        // The attempt is written to the compliance log with the producer number
        // and state
        List<ProductionBlocks> logged = db(() -> blocks.blockedAttemptsFor(agency.getId()));
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getProducerNumber()).isEqualTo("7700101");
        assertThat(logged.get(0).getState()).isEqualTo("OH");
        assertThat(logged.get(0).getBlocked()).isTrue();
        assertThat(logged.get(0).getLoggedOn()).isEqualTo(BASE_DATE);
    }

    @Test
    @DisplayName("Agency principal may bind on behalf of an unlicensed staff member @v1")
    void agencyPrincipalMayBindOnBehalfOfAnUnlicensedStaffMember() {
        // An unlicensed customer service representative who assembled a complete
        // application, and a licensed agency principal
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        newUnlicensedProducer(agency.getId(), "7700102");
        Producers principal = newLicensedProducer(agency.getId(), "7700103", "OH");

        Policies policy = insertAgencyPolicy(agency.getId(), null,
                new BigDecimal("517.00"), BASE_DATE, null);

        try {
            // The licensed agency principal reviews and binds the application
            ProductionBlocks attempt = db(() -> blocks.bindOnBehalfOfStaff(
                    principal.getId(), "OH", policy.getId()));

            // The bind succeeds under the principal's license
            assertThat(attempt.getBlocked()).isFalse();

            // The principal is recorded as the producer of record
            assertThat(db(() -> reloadPolicy(policy.getId())).getProducerOfRecordId())
                    .isEqualTo(principal.getId());
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Emergency override requires compliance approval @v1")
    void emergencyOverrideRequiresComplianceApproval() {
        // A blocked bind where the producer holds a license the registry has not
        // yet published
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newUnlicensedProducer(agency.getId(), "7700104");

        assertThatThrownBy(() -> db(() -> blocks.attemptBind(producer.getId(), "OH", null)))
                .hasMessageContaining("Bind refused");

        // A compliance officer approves a 72-hour override with documented evidence
        ComplianceOverrides override = db(() -> blocks.approveOverride(producer.getId(),
                "Morgan Reyes, Compliance", "State-issued license certificate dated 12 Jan"));

        assertThat(override.getApprovedBy()).isEqualTo("Morgan Reyes, Compliance");
        assertThat(override.getEvidence()).isNotBlank();

        // The override expires automatically after 72 hours
        assertThat(override.getExpiresAt())
                .isEqualTo(override.getApprovedAt().plusSeconds(72 * 3600));

        // The bind is permitted once
        ProductionBlocks permitted =
                db(() -> blocks.attemptBind(producer.getId(), "OH", null));
        assertThat(permitted.getBlocked()).isFalse();
        assertThat(permitted.getComplianceOverrideId()).isEqualTo(override.getId());

        // Only once: the override is consumed, so a second attempt is blocked again
        assertThatThrownBy(() -> db(() -> blocks.attemptBind(producer.getId(), "OH", null)))
                .hasMessageContaining("Bind refused");
    }

    @Test
    @DisplayName("Emergency override requires compliance approval @v1 — expires after 72 hours")
    void overrideExpiresAfterSeventyTwoHours() {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newUnlicensedProducer(agency.getId(), "7700105");
        db(() -> blocks.approveOverride(producer.getId(), "Morgan Reyes, Compliance",
                "State-issued license certificate"));

        // Four days on, the override has expired and the bind is blocked again
        clock.setBusinessDate(BASE_DATE.plusDays(4));
        try {
            assertThatThrownBy(() -> db(() -> blocks.attemptBind(producer.getId(), "OH", null)))
                    .hasMessageContaining("Bind refused");
        } finally {
            clock.setBusinessDate(BASE_DATE);
        }
    }

    @Test
    @DisplayName("Commission is withheld on business written without authority @v1")
    void commissionIsWithheldOnBusinessWrittenWithoutAuthority() {
        // A policy bound on 4 February 2026 by a producer whose license lapsed
        // 31 January 2026
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> commissions.publishSchedule(agency.getId(), "Dana Whitfield", JAN_1_2026));
        Producers producer = newLicensedProducer(agency.getId(), "7700106", "OH");
        db(() -> licensing.verify(producer.getId(), "OH", "Roberto Alvarez",
                Statuses.LICENSE_CLASS_PC, LocalDate.of(2026, 1, 31)));

        LocalDate boundOn = LocalDate.of(2026, 2, 4);
        Policies policy = insertAgencyPolicy(agency.getId(), producer.getId(),
                new BigDecimal("1000.00"), boundOn, null);

        try {
            clock.setBusinessDate(boundOn);
            // The licence had lapsed by the bind date
            assertThat(db(() -> licensing.isAuthorized(producer.getId(), "OH"))).isFalse();

            db(() -> commissions.earnNewBusiness(agency.getId(), policy.getId(),
                    Statuses.LINE_PERSONAL_AUTO, new BigDecimal("1000.00"), null, null));

            // The commission statement is calculated
            List<CommissionEntries> withheld =
                    db(() -> blocks.withholdCommission(policy.getId()));

            // Commission on that policy is withheld pending compliance review
            assertThat(withheld).hasSize(1);
            assertThat(withheld.get(0).getWithheld()).isTrue();
            assertThat(withheld.get(0).getWithheldReason())
                    .isEqualTo(Statuses.WITHHELD_PENDING_COMPLIANCE);

            // It is excluded from the statement that period
            var statement = db(() -> commissions.produceStatement(agency.getId(),
                    JAN_1_2026, boundOn.plusDays(1)));
            assertThat(statement.getEarnedAmount()).isEqualByComparingTo("0.00");

            // The policy itself remains in force to protect the insured
            assertThat(db(() -> reloadPolicy(policy.getId())).getStatus())
                    .isEqualTo(Statuses.POLICY_IN_FORCE);
        } finally {
            clock.setBusinessDate(BASE_DATE);
            deletePolicies(policy.getId());
        }
    }
}
