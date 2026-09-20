package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AggregateSnapshots;
import cds.gen.insurance.AggregateSnapshots_;
import cds.gen.insurance.CatastropheZones;
import cds.gen.insurance.CatastropheZones_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;
import cds.gen.insurance.ZoneUtilizationBands;
import cds.gen.insurance.ZoneUtilizationBands_;

/**
 * Measures accumulated insured value by catastrophe zone against capacity and
 * throttles new business as capacity fills.
 *
 * Spec: specs/underwriting/appetite/manage-catastrophe-aggregation.feature.md
 * The utilization bands are CSV seed data in
 * db/data/insurance-ZoneUtilizationBands.csv.
 */
@Component
public class CatastropheAggregationManager {

    private final PersistenceService persistence;
    private final Clock clock;

    public CatastropheAggregationManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Aggregate updated when a policy binds" — the zone aggregate increases by
     * the full dwelling and contents limits, and the snapshot is timestamped for
     * the nightly reinsurance report.
     *
     * "Earthquake and wind zones counted independently" — a risk sitting in both
     * a wind and an earthquake zone adds its insured value to BOTH aggregates,
     * and a stop in one peril does not close the other.
     */
    public List<AggregateSnapshots> addPolicyToZones(String policyId,
            java.util.Collection<String> zoneCodes, BigDecimal insuredValue) {
        LocalDateTime takenAt = LocalDateTime.now(clock);
        List<AggregateSnapshots> snapshots = new ArrayList<>();

        for (String zoneCode : zoneCodes) {
            CatastropheZones zone = zoneByCode(zoneCode);
            BigDecimal updated = zone.getAggregateTiv().add(insuredValue);
            zone.setAggregateTiv(updated);
            persistence.run(Update.entity(CatastropheZones_.class).entry(zone));

            AggregateSnapshots snapshot = AggregateSnapshots.create();
            snapshot.setId(UUID.randomUUID().toString());
            snapshot.setZoneId(zone.getId());
            snapshot.setPolicyId(policyId);
            snapshot.setAddedTiv(insuredValue);
            snapshot.setAggregateTiv(updated);
            snapshot.setTakenAt(takenAt.atZone(clock.getZone()).toInstant());
            persistence.run(Insert.into(AggregateSnapshots_.class).entry(snapshot));
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    /**
     * "Capacity threshold triggers a soft stop" — at 90% utilization new
     * business requires underwriter approval before quoting while renewals
     * continue without additional approval.
     *
     * "Hard stop closes the zone to new business" — at 98% or more the
     * submission is declined for catastrophe capacity, and the agent is shown
     * the zone identifier and expected reopen review date.
     */
    public Submissions screenCapacity(String submissionId, String zoneCode) {
        Submissions submission = loadSubmission(submissionId);
        CatastropheZones zone = zoneByCode(zoneCode);
        ZoneUtilizationBands band = bandFor(utilizationPct(zone));

        if (Statuses.CAPACITY_CLOSED.equals(band.getNewBusiness())) {
            submission.setStatus(Statuses.SUBMISSION_DECLINED);
            submission.setDeclineReason(Statuses.DECLINE_CAT_CAPACITY + " — zone "
                    + zone.getZoneCode() + ", reopen review " + zone.getReopenReviewDate());
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        } else if (Statuses.CAPACITY_RESTRICTED.equals(band.getNewBusiness())) {
            // Requires underwriter approval before quoting.
            submission.setStatus(Statuses.SUBMISSION_REFERRED);
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        }
        return loadSubmission(submissionId);
    }

    /** Zone utilization as a percentage of capacity. */
    public BigDecimal utilizationPct(CatastropheZones zone) {
        if (zone.getCapacityAmount() == null
                || zone.getCapacityAmount().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return zone.getAggregateTiv()
                .multiply(BigDecimal.valueOf(100))
                .divide(zone.getCapacityAmount(), 2, RoundingMode.HALF_UP);
    }

    /** The utilization band governing a zone, from the CSV band table. */
    public ZoneUtilizationBands bandFor(BigDecimal utilizationPct) {
        return persistence.run(Select.from(ZoneUtilizationBands_.class))
                .listOf(ZoneUtilizationBands.class).stream()
                .filter(b -> utilizationPct.compareTo(b.getMinUtilizationPct()) >= 0
                        && utilizationPct.compareTo(b.getMaxUtilizationPct()) <= 0)
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No utilization band for " + utilizationPct + "%"));
    }

    public ZoneUtilizationBands bandForZone(String zoneCode) {
        return bandFor(utilizationPct(zoneByCode(zoneCode)));
    }

    public CatastropheZones zoneByCode(String zoneCode) {
        return persistence.run(Select.from(CatastropheZones_.class)
                .where(z -> z.zoneCode().eq(zoneCode)))
                .first(CatastropheZones.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Catastrophe zone not found: " + zoneCode));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
