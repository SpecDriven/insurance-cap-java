package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.BusinessHours;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.ReferralReasons;
import cds.gen.insurance.ReferralSlaTargets;
import cds.gen.insurance.ReferralSlaTargets_;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Referrals_;
import cds.gen.insurance.Submissions;
import cds.gen.insurance.Submissions_;

/**
 * Routes submissions to underwriters and tracks the referral SLA.
 *
 * Spec: specs/underwriting/referrals/refer-to-underwriter.feature.md
 * Spec: specs/underwriting/referrals/apply-referral-sla.feature.md
 *
 * SLA targets are CSV seed data in db/data/insurance-ReferralSlaTargets.csv.
 * Elapsed time is measured in business hours (see {@link BusinessHours}) so
 * weekend and holiday hours are not counted.
 */
@Component
public class ReferralManager {

    private final PersistenceService persistence;
    private final EligibilityManager eligibility;
    private final EmailPort email;
    private final Clock clock;

    public ReferralManager(PersistenceService persistence, EligibilityManager eligibility,
            EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.eligibility = eligibility;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Submission referred for a single rule failure" — the submission moves to
     * the underwriting queue in referred status with the failing rule and its
     * evaluated values attached.
     *
     * "Multiple reasons are consolidated into one referral" — a submission
     * failing several rules opens ONE referral listing every reason in priority
     * order; a second call returns the existing referral rather than duplicating.
     *
     * "Referral routed by line of business and state" — the queue is named by
     * the caller (e.g. "Texas property") and the SLA clock starts on queueing.
     */
    public Referrals refer(String submissionId, String referralType, String queueName) {
        Submissions submission = loadSubmission(submissionId);

        // Duplicate referrals for the same submission are suppressed.
        var existing = persistence.run(Select.from(Referrals_.class)
                .where(r -> r.submission_ID().eq(submissionId)
                        .and(r.status().ne(Statuses.REFERRAL_DECIDED))))
                .first(Referrals.class);
        if (existing.isPresent()) {
            return load(existing.get().getId());
        }

        String type = referralType == null ? Statuses.REFERRAL_TYPE_STANDARD : referralType;
        ReferralSlaTargets target = slaTarget(type);
        LocalDateTime queuedAt = now();

        Referrals referral = Referrals.create();
        referral.setId(UUID.randomUUID().toString());
        referral.setSubmissionId(submissionId);
        referral.setReferralType(type);
        referral.setStatus(Statuses.REFERRAL_REFERRED);
        referral.setQueueName(queueName);
        referral.setOriginalQueueName(queueName);
        referral.setQueuedAt(queuedAt.atZone(zone()).toInstant());
        referral.setSlaTargetHours(target.getTargetHours());
        referral.setSlaDueAt(slaDueAt(queuedAt, target).atZone(zone()).toInstant());
        referral.setSlaStatus(Statuses.SLA_OPEN);
        referral.setSlaPausedHours(BigDecimal.ZERO);
        referral.setReasons(reasonsFor(submissionId));

        persistence.run(Insert.into(Referrals_.class).entry(referral));

        submission.setStatus(Statuses.SUBMISSION_REFERRED);
        persistence.run(Update.entity(Submissions_.class).entry(submission));

        return load(referral.getId());
    }

    /** Failed screening rules become referral reasons, listed in priority order. */
    private List<ReferralReasons> reasonsFor(String submissionId) {
        List<ReferralReasons> reasons = new ArrayList<>();
        var screening = persistence.run(Select.from(cds.gen.insurance.EligibilityScreenings_.class)
                .where(s -> s.submission_ID().eq(submissionId).and(s.superseded().eq(false))))
                .first(cds.gen.insurance.EligibilityScreenings.class);
        if (screening.isEmpty()) {
            return reasons;
        }
        var full = eligibility.loadScreening(screening.get().getId());
        int priority = 1;
        for (String ruleName : eligibility.failedRuleNames(full)) {
            ReferralReasons reason = ReferralReasons.create();
            reason.setId(UUID.randomUUID().toString());
            reason.setPriority(priority++);
            reason.setReasonCode(ruleName);
            reason.setEvaluatedValue("failed " + ruleName + " during screening");
            reasons.add(reason);
        }
        return reasons;
    }

    public Referrals assign(String referralId, String assignedTo) {
        Referrals referral = load(referralId);
        referral.setAssignedTo(assignedTo);
        referral.setStatus(Statuses.REFERRAL_ASSIGNED);
        persistence.run(Update.entity(Referrals_.class).entry(referral));
        return load(referralId);
    }

    /**
     * "Clock pauses while awaiting agent information" — placing the referral in
     * awaiting-agent status suspends the SLA clock.
     */
    public Referrals awaitAgentInformation(String referralId) {
        Referrals referral = load(referralId);
        referral.setStatus(Statuses.REFERRAL_AWAITING_AGENT);
        referral.setSlaPausedAt(now().atZone(zone()).toInstant());
        persistence.run(Update.entity(Referrals_.class).entry(referral));
        return load(referralId);
    }

    /**
     * The clock resumes when the requested document is uploaded; the suspended
     * business hours are accumulated and the due time pushed out by that amount.
     */
    public Referrals resumeClock(String referralId) {
        Referrals referral = load(referralId);
        if (referral.getSlaPausedAt() == null) {
            return referral;
        }
        LocalDateTime pausedAt = LocalDateTime.ofInstant(referral.getSlaPausedAt(), zone());
        double suspended = BusinessHours.between(pausedAt, now());

        BigDecimal accumulated = referral.getSlaPausedHours() == null
                ? BigDecimal.ZERO : referral.getSlaPausedHours();
        accumulated = accumulated.add(BigDecimal.valueOf(suspended))
                .setScale(2, RoundingMode.HALF_UP);

        LocalDateTime due = LocalDateTime.ofInstant(referral.getSlaDueAt(), zone());
        referral.setSlaDueAt(BusinessHours.plusBusinessHours(due, suspended)
                .atZone(zone()).toInstant());
        referral.setSlaPausedHours(accumulated);
        referral.setSlaPausedAt(null);
        referral.setStatus(referral.getAssignedTo() == null
                ? Statuses.REFERRAL_REFERRED : Statuses.REFERRAL_ASSIGNED);
        persistence.run(Update.entity(Referrals_.class).entry(referral));
        return load(referralId);
    }

    /**
     * The SLA monitor.
     *
     * "Breach escalates to the team lead" — a standard referral past its 8
     * business hour target is escalated and flagged breached, and the agent
     * receives a status update with a revised expected decision time.
     *
     * "No underwriter available in the routed queue" — a referral that cannot be
     * assigned within 4 hours moves to the overflow queue, with the original
     * routing decision retained.
     */
    public List<Referrals> runSlaMonitor() {
        List<Referrals> open = persistence.run(Select.from(Referrals_.class)
                .where(r -> r.slaStatus().eq(Statuses.SLA_OPEN)))
                .listOf(Referrals.class);

        List<Referrals> touched = new ArrayList<>();
        for (Referrals referral : open) {
            if (Statuses.REFERRAL_AWAITING_AGENT.equals(referral.getStatus())) {
                continue; // clock suspended
            }
            boolean changed = false;

            // Unassigned past the overflow window moves to the overflow queue.
            if (referral.getAssignedTo() == null && !overflowed(referral)
                    && elapsedBusinessHours(referral) >= Statuses.OVERFLOW_AFTER_HOURS) {
                referral.setQueueName(Statuses.QUEUE_OVERFLOW);
                referral.setReassignmentReason(
                        "no available underwriter in " + referral.getOriginalQueueName());
                changed = true;
            }

            // Past target without a decision: breach and escalate.
            if (elapsedBusinessHours(referral) >= referral.getSlaTargetHours()) {
                ReferralSlaTargets target = slaTarget(referral.getReferralType());
                referral.setSlaStatus(Statuses.SLA_BREACHED);
                referral.setEscalatedTo(target.getBreachEscalation());
                referral.setStatus(Statuses.REFERRAL_ESCALATED);
                notifyAgentOfRevisedTime(referral);
                changed = true;
            }

            if (changed) {
                persistence.run(Update.entity(Referrals_.class).entry(referral));
                touched.add(load(referral.getId()));
            }
        }
        return touched;
    }

    /** Business hours elapsed since queueing, less any suspended period. */
    public double elapsedBusinessHours(Referrals referral) {
        LocalDateTime queuedAt = LocalDateTime.ofInstant(referral.getQueuedAt(), zone());
        double elapsed = BusinessHours.between(queuedAt, now());
        BigDecimal paused = referral.getSlaPausedHours() == null
                ? BigDecimal.ZERO : referral.getSlaPausedHours();
        return Math.max(0d, elapsed - paused.doubleValue());
    }

    /**
     * "Standard referral answered within the target" — a decision recorded
     * inside the target marks the referral met.
     */
    void markSlaOutcomeOnDecision(Referrals referral) {
        if (Statuses.SLA_BREACHED.equals(referral.getSlaStatus())) {
            return; // already breached; the decision does not undo it
        }
        boolean withinTarget = elapsedBusinessHours(referral) <= referral.getSlaTargetHours();
        referral.setSlaStatus(withinTarget ? Statuses.SLA_MET : Statuses.SLA_BREACHED);
    }

    private void notifyAgentOfRevisedTime(Referrals referral) {
        Submissions submission = loadSubmission(referral.getSubmissionId());
        LocalDateTime revised = BusinessHours.plusBusinessHours(now(), referral.getSlaTargetHours());
        email.send(new EmailMessage(submission.getApplicantEmail(),
                "Underwriting referral update",
                "Your referral has been escalated. Revised expected decision time: " + revised));
    }

    private boolean overflowed(Referrals referral) {
        return Statuses.QUEUE_OVERFLOW.equals(referral.getQueueName());
    }

    private LocalDateTime slaDueAt(LocalDateTime queuedAt, ReferralSlaTargets target) {
        double hours = Boolean.TRUE.equals(target.getBusinessDays())
                ? target.getTargetHours()   // already expressed in business hours
                : target.getTargetHours();
        return BusinessHours.plusBusinessHours(queuedAt, hours);
    }

    private ReferralSlaTargets slaTarget(String referralType) {
        return persistence.run(Select.from(ReferralSlaTargets_.class)
                .where(t -> t.referralType().eq(referralType)))
                .first(ReferralSlaTargets.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No SLA target for referral type: " + referralType));
    }

    public Referrals load(String referralId) {
        return persistence.run(Select.from(Referrals_.class)
                .where(r -> r.ID().eq(referralId))
                .columns(r -> r._all(), r -> r.reasons().expand()))
                .first(Referrals.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Referral not found: " + referralId));
    }

    private Submissions loadSubmission(String submissionId) {
        return persistence.run(Select.from(Submissions_.class)
                .where(s -> s.ID().eq(submissionId)))
                .first(Submissions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Submission not found: " + submissionId));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private ZoneId zone() {
        return clock.getZone();
    }
}
