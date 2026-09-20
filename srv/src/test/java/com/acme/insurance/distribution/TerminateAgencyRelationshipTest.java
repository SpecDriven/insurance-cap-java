package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.BookRolls;
import cds.gen.insurance.CommissionStatements;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/distribution/agency-management/terminate-agency-relationship.feature.md
 * "Notice to affected policyholders goes out before renewal" and "Termination
 * for cause suspends binding immediately" are [proposed] and intentionally
 * absent.
 */
@DisplayName("Terminate Agency Relationship — specs/distribution/agency-management/terminate-agency-relationship.feature.md")
class TerminateAgencyRelationshipTest extends DistributionTestBase {

    private static final LocalDate JAN_1_2026 = LocalDate.of(2026, 1, 1);
    private static final LocalDate MARCH_1_2026 = LocalDate.of(2026, 3, 1);

    @Test
    @DisplayName("Termination without cause honours the notice period @v1")
    void terminationWithoutCauseHonoursTheNoticePeriod() {
        // An appointed agency with a contract requiring 90 days written notice
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);

        // Acme issues a termination notice on 1 March 2026
        Agencies terminated = db(() -> terminations.terminate(agency.getId(), false,
                MARCH_1_2026, 90));

        // The termination is effective 30 May 2026
        assertThat(terminated.getTerminationEffectiveOn())
                .isEqualTo(LocalDate.of(2026, 5, 30));
        assertThat(terminated.getTerminationForCause()).isFalse();
        assertThat(terminated.getNoticePeriodDays()).isEqualTo(90);

        // The agency may continue writing new business until the effective date:
        // its appointment is untouched by the notice
        assertThat(db(() -> appointments.activeFor(agency.getId(), "OH"))).hasSize(1);
    }

    @Test
    @DisplayName("Book rolls to a replacement agency at renewal @v1")
    void bookRollsToAReplacementAgencyAtRenewal() {
        // A terminated agency with in-force personal auto policies
        Agencies terminated = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Agencies replacement = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> commissions.publishSchedule(replacement.getId(), "Dana Whitfield", JAN_1_2026));

        LocalDate effective = BASE_DATE.minusMonths(2);
        Policies first = insertAgencyPolicy(terminated.getId(), null,
                new BigDecimal("517.00"), effective, null);
        Policies second = insertAgencyPolicy(terminated.getId(), null,
                new BigDecimal("642.00"), effective.minusMonths(1), null);

        try {
            db(() -> terminations.terminate(terminated.getId(), false, MARCH_1_2026, 90));

            // A replacement agency of record is designated
            List<BookRolls> rolls = db(() -> terminations.designateReplacement(
                    terminated.getId(), replacement.getId()));

            // Each policy transfers to the replacement agency at its next renewal
            assertThat(rolls).hasSize(2);
            assertThat(rolls).allSatisfy(roll -> {
                assertThat(roll.getToAgencyId()).isEqualTo(replacement.getId());
                assertThat(roll.getStatus()).isEqualTo(Statuses.ROLL_SCHEDULED);
            });
            BookRolls firstRoll = rolls.stream()
                    .filter(r -> first.getId().equals(r.getPolicyId()))
                    .findFirst().orElseThrow();
            // Scheduled for the policy's own expiration, not an immediate transfer
            assertThat(firstRoll.getScheduledFor()).isEqualTo(first.getExpirationDate());

            // Commission on renewals after transfer accrues to the replacement agency
            var renewalCommission = db(() -> commissions.earnNewBusiness(replacement.getId(),
                    first.getId(), Statuses.LINE_PERSONAL_AUTO, new BigDecimal("517.00"),
                    null, null));
            assertThat(renewalCommission.get(0).getAgencyId()).isEqualTo(replacement.getId());
        } finally {
            deletePolicies(first.getId(), second.getId());
        }
    }

    @Test
    @DisplayName("Insured may elect to follow the terminated agency @v1")
    void insuredMayElectToFollowTheTerminatedAgency() {
        // An insured whose policy is scheduled to roll to a replacement agency
        Agencies terminated = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Agencies replacement = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Agencies elected = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);

        Policies policy = insertAgencyPolicy(terminated.getId(), null,
                new BigDecimal("517.00"), BASE_DATE.minusMonths(2), null);

        try {
            db(() -> terminations.terminate(terminated.getId(), false, MARCH_1_2026, 90));
            db(() -> terminations.designateReplacement(terminated.getId(),
                    replacement.getId()));

            // The insured returns a signed agent-of-record letter naming a
            // different agency
            LocalDate receivedOn = BASE_DATE.plusDays(12);
            BookRolls roll = db(() -> terminations.recordAgentOfRecordLetter(
                    policy.getId(), elected.getId(), receivedOn));

            // The agent-of-record letter takes precedence over the default roll
            assertThat(roll.getToAgencyId()).isEqualTo(elected.getId());
            assertThat(roll.getAgentOfRecordLetter()).isTrue();
            assertThat(roll.getStatus()).isEqualTo(Statuses.ROLL_OVERRIDDEN);

            // The change is effective on the date the letter is received
            assertThat(roll.getEffectiveDate()).isEqualTo(receivedOn);
            assertThat(db(() -> reloadPolicy(policy.getId())).getProducingAgencyId())
                    .isEqualTo(elected.getId());
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Unpaid premium owed by the agency is recovered @v1")
    void unpaidPremiumOwedByTheAgencyIsRecovered() {
        // A terminated agency holding $14,720 in collected but unremitted premium
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> commissions.publishSchedule(agency.getId(), "Dana Whitfield", JAN_1_2026));
        db(() -> {
            Agencies loaded = onboarding.load(agency.getId());
            loaded.setUnremittedPremium(new BigDecimal("14720.00"));
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Agencies_.class).entry(loaded));
            return loaded;
        });

        Policies policy = insertAgencyPolicy(agency.getId(), null,
                new BigDecimal("50000.00"), BASE_DATE, null);

        try {
            // $50,000 at 12% earns $6,000 of commission in the period
            db(() -> commissions.earnNewBusiness(agency.getId(), policy.getId(),
                    Statuses.LINE_PERSONAL_AUTO, new BigDecimal("50000.00"), null, null));

            db(() -> terminations.terminate(agency.getId(), false, MARCH_1_2026, 90));

            // The final commission statement is produced
            CommissionStatements statement = db(() -> terminations.produceFinalStatement(
                    agency.getId(), JAN_1_2026, BASE_DATE.plusDays(1)));

            // The outstanding premium is offset against earned commission
            assertThat(statement.getEarnedAmount()).isEqualByComparingTo("6000.00");
            assertThat(statement.getPremiumOffset()).isEqualByComparingTo("14720.00");
            assertThat(statement.getNetAmount()).isEqualByComparingTo("-8720.00");
            assertThat(statement.getPaymentIssued()).isFalse();

            // Any remaining shortfall is referred to collections
            assertThat(db(() -> onboarding.load(agency.getId())).getCollectionsReferral())
                    .isEqualByComparingTo("8720.00");
        } finally {
            deletePolicies(policy.getId());
        }
    }
}
