package com.acme.insurance.underwriting;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AgencyTerritoryQuotas;
import cds.gen.insurance.AgencyTerritoryQuotas_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;
import cds.gen.insurance.TerritoryRestrictions;
import cds.gen.insurance.TerritoryRestrictions_;

/**
 * Territory-level controls layered on the published appetite: closed
 * territories and per-agency writing quotas.
 *
 * Spec: specs/underwriting/appetite/restrict-by-territory.feature.md
 */
@Component
public class TerritoryManager {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PersistenceService persistence;
    private final EmailPort email;
    private final Clock clock;

    public TerritoryManager(PersistenceService persistence, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Territory closed to new personal auto business" — a submission garaged in
     * a closed territory is declined with reason "territory closed to new
     * business" and the agent is shown the closure effective date.
     *
     * "Limited writings quota per agency" — the 26th policy in a month against a
     * quota of 25 is held until the quota resets on the first of the month, and
     * the agency principal receives a quota notice.
     *
     * "Renewals continue in a closed territory" — this gate applies to NEW
     * business only; renewals are not routed through it.
     */
    public Submissions screen(String submissionId) {
        Submissions submission = loadSubmission(submissionId);
        LocalDate today = LocalDate.now(clock);

        TerritoryRestrictions restriction = restrictionFor(
                submission.getTerritoryCode(), submission.getLineOfBusiness(), today);
        if (restriction != null) {
            submission.setStatus(Statuses.SUBMISSION_DECLINED);
            submission.setDeclineReason(Statuses.DECLINE_TERRITORY_CLOSED
                    + " (effective " + restriction.getEffectiveDate() + ")");
            persistence.run(Update.entity(Submissions_.class).entry(submission));
            return loadSubmission(submissionId);
        }

        AgencyTerritoryQuotas quota = quotaFor(submission.getAgencyId(),
                submission.getTerritoryCode(), today);
        if (quota != null && quota.getUsedCount() >= quota.getMonthlyQuota()) {
            submission.setStatus(Statuses.SUBMISSION_HELD);
            submission.setDeclineReason("monthly writing quota reached for territory "
                    + submission.getTerritoryCode() + "; resets "
                    + today.plusMonths(1).withDayOfMonth(1));
            persistence.run(Update.entity(Submissions_.class).entry(submission));

            email.send(new EmailMessage(quota.getPrincipalEmail(),
                    "Territory writing quota reached",
                    "Your agency has reached its monthly quota of " + quota.getMonthlyQuota()
                            + " new policies in territory " + quota.getTerritoryCode()
                            + ". Submissions are held until the quota resets on "
                            + today.plusMonths(1).withDayOfMonth(1) + "."));
            return loadSubmission(submissionId);
        }

        if (quota != null) {
            quota.setUsedCount(quota.getUsedCount() + 1);
            persistence.run(Update.entity(AgencyTerritoryQuotas_.class).entry(quota));
        }
        return loadSubmission(submissionId);
    }

    /**
     * "Territory reopened after a rate filing is approved" — the territory
     * reopens on the filing's effective date, and held submissions are released
     * for requoting at the approved rate.
     */
    public TerritoryRestrictions reopen(String territoryCode, String lineOfBusiness,
            LocalDate reopenDate) {
        TerritoryRestrictions restriction = persistence.run(Select.from(TerritoryRestrictions_.class)
                .where(t -> t.territoryCode().eq(territoryCode)
                        .and(t.lineOfBusiness().eq(lineOfBusiness))))
                .first(TerritoryRestrictions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No restriction for territory " + territoryCode));

        restriction.setStatus(Statuses.TERRITORY_OPEN);
        restriction.setReopenDate(reopenDate);
        persistence.run(Update.entity(TerritoryRestrictions_.class).entry(restriction));

        releaseHeldSubmissions(territoryCode);

        return persistence.run(Select.from(TerritoryRestrictions_.class)
                .where(t -> t.ID().eq(restriction.getId())))
                .single(TerritoryRestrictions.class);
    }

    /** Held submissions in the territory return to screening for requoting. */
    private void releaseHeldSubmissions(String territoryCode) {
        List<Submissions> held = persistence.run(Select.from(Submissions_.class)
                .where(s -> s.territoryCode().eq(territoryCode)
                        .and(s.status().eq(Statuses.SUBMISSION_DECLINED)
                                .or(s.status().eq(Statuses.SUBMISSION_HELD)))))
                .listOf(Submissions.class);
        for (Submissions submission : held) {
            submission.setStatus(Statuses.SUBMISSION_SCREENING);
            submission.setDeclineReason(null);
            persistence.run(Update.entity(Submissions_.class).entry(submission));
        }
    }

    /** An active closure for the territory and line on the given date, if any. */
    private TerritoryRestrictions restrictionFor(String territoryCode, String lineOfBusiness,
            LocalDate on) {
        if (territoryCode == null) {
            return null;
        }
        return persistence.run(Select.from(TerritoryRestrictions_.class)
                .where(t -> t.territoryCode().eq(territoryCode)
                        .and(t.status().eq(Statuses.TERRITORY_CLOSED))))
                .listOf(TerritoryRestrictions.class).stream()
                .filter(t -> t.getLineOfBusiness() == null
                        || t.getLineOfBusiness().equalsIgnoreCase(lineOfBusiness))
                .filter(t -> t.getEffectiveDate() == null || !on.isBefore(t.getEffectiveDate()))
                .filter(t -> t.getReopenDate() == null || on.isBefore(t.getReopenDate()))
                .findFirst()
                .orElse(null);
    }

    private AgencyTerritoryQuotas quotaFor(String agencyId, String territoryCode, LocalDate on) {
        if (agencyId == null || territoryCode == null) {
            return null;
        }
        String period = on.format(PERIOD);
        return persistence.run(Select.from(AgencyTerritoryQuotas_.class)
                .where(q -> q.agencyId().eq(agencyId)
                        .and(q.territoryCode().eq(territoryCode))
                        .and(q.periodMonth().eq(period))))
                .first(AgencyTerritoryQuotas.class)
                .orElse(null);
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }
}
