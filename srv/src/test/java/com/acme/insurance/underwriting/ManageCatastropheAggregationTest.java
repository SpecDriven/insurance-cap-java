package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.AggregateSnapshots;
import cds.gen.insurance.CatastropheZones;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
 * "Aggregate released when a policy cancels", "Aggregation feed fails
 * overnight", and "Reinsurance treaty renewal resets capacity" are [proposed]
 * and intentionally absent.
 */
@DisplayName("Manage Catastrophe Aggregation — specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md")
class ManageCatastropheAggregationTest extends UnderwritingTestBase {

    @Test
    @DisplayName("Aggregate updated when a policy binds @v1")
    void aggregateUpdatedWhenAPolicyBinds() {
        // A wind zone with $412,000,000 of aggregate total insured value
        insertZone("WIND-TX-04", Statuses.ZONE_PERIL_WIND,
                new BigDecimal("500000000.00"), new BigDecimal("412000000.00"));
        Policies policy = insertHomePolicy(null);

        // A $780,000 dwelling binds in that zone
        List<AggregateSnapshots> snapshots = db(() -> aggregation.addPolicyToZones(
                policy.getId(), List.of("WIND-TX-04"), new BigDecimal("780000.00")));

        // The zone aggregate increases by the full dwelling and contents limits
        CatastropheZones zone = db(() -> aggregation.zoneByCode("WIND-TX-04"));
        assertThat(zone.getAggregateTiv())
                .isEqualByComparingTo(new BigDecimal("412780000.00"));

        // The aggregate snapshot is timestamped for the nightly reinsurance report
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getTakenAt()).isNotNull();
        assertThat(snapshots.get(0).getAddedTiv())
                .isEqualByComparingTo(new BigDecimal("780000.00"));
    }

    @Test
    @DisplayName("Earthquake and wind zones counted independently @v1")
    void earthquakeAndWindZonesCountedIndependently() {
        // A risk sitting in both a wind zone and an earthquake zone
        insertZone("WIND-CA-02", Statuses.ZONE_PERIL_WIND,
                new BigDecimal("500000000.00"), new BigDecimal("100000000.00"));
        // The earthquake zone is already closed by a hard stop
        insertZone("EQ-CA-07", Statuses.ZONE_PERIL_EARTHQUAKE,
                new BigDecimal("500000000.00"), new BigDecimal("492000000.00"));
        Policies policy = insertHomePolicy(null);

        // When the policy binds, its insured value is added to both zone aggregates
        db(() -> aggregation.addPolicyToZones(policy.getId(),
                List.of("WIND-CA-02", "EQ-CA-07"), new BigDecimal("600000.00")));

        assertThat(db(() -> aggregation.zoneByCode("WIND-CA-02")).getAggregateTiv())
                .isEqualByComparingTo(new BigDecimal("100600000.00"));
        assertThat(db(() -> aggregation.zoneByCode("EQ-CA-07")).getAggregateTiv())
                .isEqualByComparingTo(new BigDecimal("492600000.00"));

        // A stop in one peril does not close the other
        assertThat(db(() -> aggregation.bandForZone("EQ-CA-07")).getNewBusiness())
                .isEqualTo(Statuses.CAPACITY_CLOSED);
        assertThat(db(() -> aggregation.bandForZone("WIND-CA-02")).getNewBusiness())
                .isEqualTo(Statuses.CAPACITY_OPEN);
    }

    @Test
    @DisplayName("Capacity threshold triggers a soft stop @v1")
    void capacityThresholdTriggersASoftStop() {
        // A zone capacity of $500,000,000 with a soft stop at 90%; the aggregate
        // reaches $451,000,000 (90.2%)
        insertZone("WIND-FL-01", Statuses.ZONE_PERIL_WIND,
                new BigDecimal("500000000.00"), new BigDecimal("451000000.00"));

        var band = db(() -> aggregation.bandForZone("WIND-FL-01"));

        // New business in the zone requires underwriter approval before quoting
        assertThat(band.getNewBusiness()).isEqualTo(Statuses.CAPACITY_RESTRICTED);
        assertThat(band.getApprovalRequired()).isEqualTo(Statuses.APPROVAL_UNDERWRITER);

        // Renewals continue to process without additional approval
        assertThat(band.getRenewals()).isEqualTo(Statuses.CAPACITY_OPEN);

        // A new submission is referred for that underwriter approval
        Submissions submission = newHomeSubmission();
        assertThat(db(() -> aggregation.screenCapacity(submission.getId(), "WIND-FL-01"))
                .getStatus()).isEqualTo(Statuses.SUBMISSION_REFERRED);
    }

    @Test
    @DisplayName("Hard stop closes the zone to new business @v1")
    void hardStopClosesTheZoneToNewBusiness() {
        // A zone aggregate at 98.4% of capacity
        CatastropheZones zone = insertZone("WIND-NC-03", Statuses.ZONE_PERIL_WIND,
                new BigDecimal("500000000.00"), new BigDecimal("492000000.00"));
        assertThat(db(() -> aggregation.utilizationPct(aggregation.zoneByCode("WIND-NC-03"))))
                .isEqualByComparingTo(new BigDecimal("98.40"));

        // A new submission is received for that zone
        Submissions submission = newHomeSubmission();
        Submissions screened =
                db(() -> aggregation.screenCapacity(submission.getId(), "WIND-NC-03"));

        // The submission is declined for catastrophe capacity
        assertThat(screened.getStatus()).isEqualTo(Statuses.SUBMISSION_DECLINED);
        assertThat(screened.getDeclineReason()).startsWith(Statuses.DECLINE_CAT_CAPACITY);

        // The agent is shown the zone identifier and expected reopen review date
        assertThat(screened.getDeclineReason())
                .contains("WIND-NC-03")
                .contains(zone.getReopenReviewDate().toString());
    }
}
