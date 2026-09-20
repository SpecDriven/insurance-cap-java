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
import cds.gen.insurance.CommissionEntries;
import cds.gen.insurance.CommissionSchedules;
import cds.gen.insurance.ContingentCommissions;
import cds.gen.insurance.Policies;

/**
 * Spec: specs/distribution/agency-management/set-commission-schedule.feature.md
 * "Mid-term rate change does not disturb prior transactions" and "Negative
 * statement balance carries forward" are [proposed] and intentionally absent.
 */
@DisplayName("Set Agency Commission Schedule — specs/distribution/agency-management/set-commission-schedule.feature.md")
class SetCommissionScheduleTest extends DistributionTestBase {

    private static final LocalDate JAN_1_2026 = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("New business and renewal rates differ by line @v1")
    void newBusinessAndRenewalRatesDifferByLine() {
        // A Silver-tier agency appointed for personal auto and homeowners
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO,
                Statuses.LINE_HOMEOWNERS);
        db(() -> onboarding.confirmProductionCommitment(agency.getId(),
                new BigDecimal("750000.00")));

        // The distribution manager publishes the standard schedule effective 1 January 2026
        List<CommissionSchedules> published = db(() -> commissions.publishSchedule(
                agency.getId(), "Dana Whitfield", JAN_1_2026));

        // Each line carries distinct new business and renewal rates (see the table)
        assertThat(published).extracting(CommissionSchedules::getLineOfBusiness)
                .contains(Statuses.LINE_PERSONAL_AUTO, Statuses.LINE_HOMEOWNERS,
                        Statuses.LINE_RENTERS, Statuses.LINE_UMBRELLA);

        CommissionSchedules auto = lineOf(published, Statuses.LINE_PERSONAL_AUTO);
        assertThat(auto.getNewBusinessPct()).isEqualByComparingTo("12.00");
        assertThat(auto.getRenewalPct()).isEqualByComparingTo("10.00");
        assertThat(auto.getEndorsementPct()).isEqualByComparingTo("10.00");

        CommissionSchedules home = lineOf(published, Statuses.LINE_HOMEOWNERS);
        assertThat(home.getNewBusinessPct()).isEqualByComparingTo("15.00");
        assertThat(home.getRenewalPct()).isEqualByComparingTo("13.00");

        CommissionSchedules renters = lineOf(published, Statuses.LINE_RENTERS);
        assertThat(renters.getNewBusinessPct()).isEqualByComparingTo("18.00");
        assertThat(renters.getRenewalPct()).isEqualByComparingTo("15.00");

        CommissionSchedules umbrella = lineOf(published, Statuses.LINE_UMBRELLA);
        assertThat(umbrella.getNewBusinessPct()).isEqualByComparingTo("11.00");
        assertThat(umbrella.getRenewalPct()).isEqualByComparingTo("9.00");

        // The schedule applies to policies with an effective date on or after
        // 1 January 2026
        assertThat(published).allSatisfy(s ->
                assertThat(s.getEffectiveDate()).isEqualTo(JAN_1_2026));
        assertThat(db(() -> commissions.rateFor(agency.getId(), Statuses.LINE_PERSONAL_AUTO,
                Statuses.TXN_NEW_BUSINESS, JAN_1_2026))).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("Chargeback follows a flat-cancelled policy @v1")
    void chargebackFollowsAFlatCancelledPolicy() {
        // A bound policy that earned the agency $184 of new business commission
        Agencies agency = scheduledAgency();
        Policies policy = insertAgencyPolicy(agency.getId(), null,
                new BigDecimal("1533.34"), BASE_DATE, null);

        try {
            // $1,533.34 at the 12% personal auto rate earns $184.00
            List<CommissionEntries> earned = db(() -> commissions.earnNewBusiness(
                    agency.getId(), policy.getId(), Statuses.LINE_PERSONAL_AUTO,
                    new BigDecimal("1533.34"), null, null));
            assertThat(earned).hasSize(1);
            assertThat(earned.get(0).getAmount()).isEqualByComparingTo("184.00");

            // The policy is flat cancelled within the 10-day free-look period
            List<CommissionEntries> chargebacks = db(() -> commissions.chargeback(
                    policy.getId(), Statuses.CANCELLATION_FLAT, null));

            // The full $184 is charged back on the next commission statement
            assertThat(chargebacks).hasSize(1);
            assertThat(chargebacks.get(0).getAmount()).isEqualByComparingTo("-184.00");
            assertThat(chargebacks.get(0).getTransactionType())
                    .isEqualTo(Statuses.TXN_CHARGEBACK);
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Chargeback follows a flat-cancelled policy @v1 — short-rate charges back the unearned portion")
    void shortRateCancellationChargesBackOnlyTheUnearnedPortion() {
        // A short-rate cancellation would instead charge back only the unearned portion
        Agencies agency = scheduledAgency();
        Policies policy = insertAgencyPolicy(agency.getId(), null,
                new BigDecimal("1533.34"), BASE_DATE, null);

        try {
            db(() -> commissions.earnNewBusiness(agency.getId(), policy.getId(),
                    Statuses.LINE_PERSONAL_AUTO, new BigDecimal("1533.34"), null, null));

            // 75% of the term remains unearned at cancellation
            List<CommissionEntries> chargebacks = db(() -> commissions.chargeback(
                    policy.getId(), Statuses.CANCELLATION_SHORT_RATE, new BigDecimal("75.00")));

            // Only the unearned portion of the $184 comes back
            assertThat(chargebacks.get(0).getAmount()).isEqualByComparingTo("-138.00");
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Contingent commission requires a loss ratio threshold @v1")
    void contingentCommissionRequiresALossRatioThreshold() {
        // An agency with $1,400,000 of earned premium in the 2025 contingent year
        // and a book loss ratio of 47% including loss adjustment expense
        Agencies agency = scheduledAgency();
        db(() -> onboarding.confirmProductionCommitment(agency.getId(),
                new BigDecimal("1400000.00")));

        // The contingent calculation runs after the 90-day development window
        ContingentCommissions contingent = db(() -> commissions.calculateContingent(
                agency.getId(), 2025, new BigDecimal("1400000.00"),
                new BigDecimal("658000.00")));

        assertThat(contingent.getLossRatioPct()).isEqualByComparingTo("47.00");

        // A contingent commission of 3% of earned premium is accrued
        assertThat(contingent.getRatePct()).isEqualByComparingTo("3.00");
        assertThat(contingent.getAccruedAmount()).isEqualByComparingTo("42000.00");
    }

    @Test
    @DisplayName("Contingent commission requires a loss ratio threshold @v1 — none at 60% or higher")
    void noContingentIsPaidAtSixtyPercentOrHigher() {
        Agencies agency = scheduledAgency();
        db(() -> onboarding.confirmProductionCommitment(agency.getId(),
                new BigDecimal("1400000.00")));

        // No contingent is paid when the loss ratio is 60% or higher
        ContingentCommissions atThreshold = db(() -> commissions.calculateContingent(
                agency.getId(), 2025, new BigDecimal("1400000.00"),
                new BigDecimal("840000.00")));

        assertThat(atThreshold.getLossRatioPct()).isEqualByComparingTo("60.00");
        assertThat(atThreshold.getRatePct()).isEqualByComparingTo("0.00");
        assertThat(atThreshold.getAccruedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Sub-producer split is honoured on shared business @v1")
    void subProducerSplitIsHonouredOnSharedBusiness() {
        // A placement shared between the writing agency and a referring agency
        // at a 70/30 split
        Agencies writing = scheduledAgency();
        Agencies referring = scheduledAgency();
        Policies policy = insertAgencyPolicy(writing.getId(), null,
                new BigDecimal("2000.00"), BASE_DATE, null);

        try {
            // Commission is calculated on the bound premium
            List<CommissionEntries> entries = db(() -> commissions.earnNewBusiness(
                    writing.getId(), policy.getId(), Statuses.LINE_PERSONAL_AUTO,
                    new BigDecimal("2000.00"), referring.getId(), new BigDecimal("70.00")));

            // Each agency code receives its share on its own statement
            assertThat(entries).hasSize(2);
            CommissionEntries writingEntry = entries.stream()
                    .filter(e -> writing.getId().equals(e.getAgencyId()))
                    .findFirst().orElseThrow();
            CommissionEntries referringEntry = entries.stream()
                    .filter(e -> referring.getId().equals(e.getAgencyId()))
                    .findFirst().orElseThrow();

            // 12% of $2,000 is $240, split 70/30
            assertThat(writingEntry.getAmount()).isEqualByComparingTo("168.00");
            assertThat(referringEntry.getAmount()).isEqualByComparingTo("72.00");

            // The split is recorded on the policy for audit
            assertThat(writingEntry.getSharePct()).isEqualByComparingTo("70.00");
            assertThat(referringEntry.getSharePct()).isEqualByComparingTo("30.00");
            assertThat(writingEntry.getPolicyId()).isEqualTo(policy.getId());
            assertThat(referringEntry.getPolicyId()).isEqualTo(policy.getId());
        } finally {
            deletePolicies(policy.getId());
        }
    }

    /** An agency with the standard schedule published and in force. */
    private Agencies scheduledAgency() {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> commissions.publishSchedule(agency.getId(), "Dana Whitfield", JAN_1_2026));
        return agency;
    }

    private static CommissionSchedules lineOf(List<CommissionSchedules> schedules, String line) {
        return schedules.stream()
                .filter(s -> line.equals(s.getLineOfBusiness()))
                .findFirst().orElseThrow();
    }
}
