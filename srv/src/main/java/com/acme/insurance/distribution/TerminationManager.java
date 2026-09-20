package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.LocalDate;
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

import cds.gen.insurance.Agencies;
import cds.gen.insurance.Agencies_;
import cds.gen.insurance.BookRolls;
import cds.gen.insurance.BookRolls_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * Ending an agency relationship: the notice period, the roll of the in-force
 * book to a replacement agency, and the insured's right to elect otherwise.
 *
 * Spec: specs/distribution/agency-management/terminate-agency-relationship.feature.md
 */
@Component
public class TerminationManager {

    private final PersistenceService persistence;
    private final AgencyOnboardingManager agencies;
    private final CommissionManager commissions;
    private final Clock clock;

    public TerminationManager(PersistenceService persistence, AgencyOnboardingManager agencies,
            CommissionManager commissions, Clock clock) {
        this.persistence = persistence;
        this.agencies = agencies;
        this.commissions = commissions;
        this.clock = clock;
    }

    /**
     * "Termination without cause honours the notice period" — a notice issued on
     * 1 March 2026 under a 90-day contract is effective 30 May 2026, and the
     * agency may continue writing new business until that date.
     */
    public Agencies terminate(String agencyId, Boolean forCause, LocalDate noticeDate,
            Integer noticePeriodDays) {
        Agencies agency = agencies.load(agencyId);
        boolean cause = Boolean.TRUE.equals(forCause);

        agency.setStatus(Statuses.AGENCY_TERMINATED);
        agency.setTerminationForCause(cause);
        agency.setTerminationNoticeOn(noticeDate);
        agency.setNoticePeriodDays(cause ? 0 : noticePeriodDays);
        // For cause, binding authority is revoked the same day; that scenario is
        // [proposed], so only the without-cause notice period is computed here.
        agency.setTerminationEffectiveOn(
                cause ? noticeDate : noticeDate.plusDays(noticePeriodDays));

        persistence.run(Update.entity(Agencies_.class).entry(agency));
        return agencies.load(agencyId);
    }

    /**
     * "Book rolls to a replacement agency at renewal" — each in-force policy of
     * the terminated agency transfers to the replacement agency at its next
     * renewal, and commission on renewals after transfer accrues to the
     * replacement agency.
     */
    public List<BookRolls> designateReplacement(String terminatedAgencyId,
            String replacementAgencyId) {
        agencies.load(terminatedAgencyId);
        agencies.load(replacementAgencyId);

        List<BookRolls> rolls = new ArrayList<>();
        for (Policies policy : inForceBook(terminatedAgencyId)) {
            BookRolls roll = BookRolls.create();
            roll.setId(UUID.randomUUID().toString());
            roll.setPolicyId(policy.getId());
            roll.setFromAgencyId(terminatedAgencyId);
            roll.setToAgencyId(replacementAgencyId);
            roll.setStatus(Statuses.ROLL_SCHEDULED);
            // The transfer happens at the policy's next renewal, not immediately.
            roll.setScheduledFor(policy.getExpirationDate());
            roll.setAgentOfRecordLetter(false);
            persistence.run(Insert.into(BookRolls_.class).entry(roll));
            rolls.add(load(roll.getId()));
        }
        return rolls;
    }

    /**
     * "Insured may elect to follow the terminated agency" — a signed
     * agent-of-record letter naming a different agency takes precedence over the
     * default roll, effective on the date the letter is received.
     */
    public BookRolls recordAgentOfRecordLetter(String policyId, String electedAgencyId,
            LocalDate receivedOn) {
        agencies.load(electedAgencyId);

        BookRolls scheduled = persistence.run(Select.from(BookRolls_.class)
                .where(r -> r.policy_ID().eq(policyId)))
                .listOf(BookRolls.class).stream()
                .filter(r -> Statuses.ROLL_SCHEDULED.equals(r.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No scheduled roll for policy " + policyId));

        scheduled.setToAgencyId(electedAgencyId);
        scheduled.setAgentOfRecordLetter(true);
        scheduled.setStatus(Statuses.ROLL_OVERRIDDEN);
        // The letter takes precedence over the default roll from the date received.
        scheduled.setEffectiveDate(receivedOn);
        persistence.run(Update.entity(BookRolls_.class).entry(scheduled));

        Policies policy = policy(policyId);
        policy.setProducingAgencyId(electedAgencyId);
        persistence.run(Update.entity(Policies_.class).entry(policy));

        return load(scheduled.getId());
    }

    /**
     * "Unpaid premium owed by the agency is recovered" — the final commission
     * statement offsets the outstanding premium against earned commission and
     * refers any remaining shortfall to collections.
     */
    public cds.gen.insurance.CommissionStatements produceFinalStatement(String agencyId,
            LocalDate periodStart, LocalDate periodEnd) {
        return commissions.produceStatement(agencyId, periodStart, periodEnd);
    }

    /** In-force policies produced by the agency. */
    public List<Policies> inForceBook(String agencyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.producingAgency_ID().eq(agencyId)))
                .listOf(Policies.class).stream()
                .filter(p -> Statuses.POLICY_IN_FORCE.equals(p.getStatus()))
                .toList();
    }

    public BookRolls load(String rollId) {
        return persistence.run(Select.from(BookRolls_.class).where(r -> r.ID().eq(rollId)))
                .first(BookRolls.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Book roll not found: " + rollId));
    }

    private Policies policy(String policyId) {
        return persistence.run(Select.from(Policies_.class).where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));
    }

    /** Clock hook for date-dependent extensions; kept for symmetry with peers. */
    protected LocalDate today() {
        return LocalDate.now(clock);
    }
}
