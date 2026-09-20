package com.acme.insurance.distribution;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.BusinessHours;
import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.PortalQuotes;
import cds.gen.insurance.PortalReferralTiers;
import cds.gen.insurance.PortalReferralTiers_;
import cds.gen.insurance.PortalReferrals;
import cds.gen.insurance.PortalReferrals_;
import cds.gen.insurance.Producers;
import cds.gen.insurance.ReferralTimelineEvents;
import cds.gen.insurance.ReferralTimelineEvents_;

/**
 * Risks outside the producer's binding authority leave the portal and enter an
 * underwriting queue. The producer keeps visibility of the submission
 * throughout, on the timeline.
 *
 * Spec: specs/distribution/agent-portal/hand-off-quote-to-underwriter.feature.md
 */
@Component
public class PortalReferralManager {

    private final PersistenceService persistence;
    private final PortalQuotingManager portalQuotes;
    private final ProducerLicensingManager licensing;
    private final Clock clock;

    public PortalReferralManager(PersistenceService persistence,
            PortalQuotingManager portalQuotes, ProducerLicensingManager licensing, Clock clock) {
        this.persistence = persistence;
        this.portalQuotes = portalQuotes;
        this.licensing = licensing;
        this.clock = clock;
    }

    /**
     * "Premium above binding authority is referred" — a commercial auto quote
     * rating at $38,400 against $25,000 of binding authority is referred to an
     * underwriter rather than bound, with the reason "exceeds producer binding
     * authority" attached.
     *
     * The referral tier follows the premium band, which sets the acknowledgement
     * SLA used by the monitor below.
     */
    public PortalReferrals handOff(String portalQuoteId, String producerId,
            BigDecimal annualPremium, String classCode) {

        Producers producer = licensing.load(producerId);
        PortalQuotes quote = portalQuotes.load(portalQuoteId);
        Instant now = clock.instant();

        BigDecimal authority = producer.getBindingAuthority();
        if (authority != null && annualPremium.compareTo(authority) <= 0) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Premium " + annualPremium + " is within the producer's binding authority "
                            + authority + "; bind it rather than referring.");
        }

        PortalReferralTiers tier = tierFor(annualPremium);

        PortalReferrals referral = PortalReferrals.create();
        referral.setId(UUID.randomUUID().toString());
        referral.setPortalQuoteId(portalQuoteId);
        referral.setAgencyId(quote.getAgencyId());
        referral.setProducerId(producerId);
        referral.setAnnualPremium(annualPremium);
        referral.setClassCode(classCode);
        referral.setTierName(tier.getTierName());
        referral.setStatus(Statuses.PORTAL_REFERRAL_REFERRED);
        referral.setReason(Statuses.REFERRAL_EXCEEDS_BINDING_AUTHORITY);
        referral.setReferredAt(now);
        referral.setAcknowledgementDueAt(acknowledgementDueAt(now, tier));
        referral.setPausedHours(BigDecimal.ZERO);
        persistence.run(Insert.into(PortalReferrals_.class).entry(referral));

        addTimelineEvent(referral.getId(), "Referred",
                "Referred to underwriting — " + Statuses.REFERRAL_EXCEEDS_BINDING_AUTHORITY, now);

        return load(referral.getId());
    }

    /**
     * "Missing loss runs stop the underwriting clock" — requesting the missing
     * loss runs moves the submission to awaiting-information and pauses the
     * decision clock.
     */
    public PortalReferrals requestLossRuns(String referralId, Integer yearsRequested) {
        PortalReferrals referral = load(referralId);
        Instant now = clock.instant();

        referral.setStatus(Statuses.PORTAL_REFERRAL_AWAITING_INFO);
        referral.setClockPausedAt(now);
        persistence.run(Update.entity(PortalReferrals_.class).entry(referral));

        addTimelineEvent(referralId, "InformationRequested",
                yearsRequested + " years of loss runs requested", now);
        return load(referralId);
    }

    /**
     * "Missing loss runs stop the underwriting clock" — the clock resumes when
     * the producer uploads the loss runs; the time spent awaiting information is
     * added to the acknowledgement deadline rather than counted against it.
     */
    public PortalReferrals uploadLossRuns(String referralId) {
        PortalReferrals referral = load(referralId);
        if (!Statuses.PORTAL_REFERRAL_AWAITING_INFO.equals(referral.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Referral " + referralId + " is not awaiting information");
        }
        Instant now = clock.instant();

        double paused = BusinessHours.between(local(referral.getClockPausedAt()), local(now));
        BigDecimal pausedTotal = referral.getPausedHours()
                .add(BigDecimal.valueOf(paused).setScale(2, java.math.RoundingMode.HALF_UP));

        referral.setStatus(Statuses.PORTAL_REFERRAL_REFERRED);
        referral.setClockResumedAt(now);
        referral.setPausedHours(pausedTotal);
        if (referral.getAcknowledgementDueAt() != null) {
            referral.setAcknowledgementDueAt(instant(BusinessHours.plusBusinessHours(
                    local(referral.getAcknowledgementDueAt()), paused)));
        }
        persistence.run(Update.entity(PortalReferrals_.class).entry(referral));

        addTimelineEvent(referralId, "InformationReceived", "Loss runs uploaded by the producer",
                now);
        return load(referralId);
    }

    /**
     * "Declined submission records an appetite reason" — the decline reason and
     * class code are recorded on the submission, and the producer is advised the
     * risk may be placed through surplus lines.
     */
    public PortalReferrals decline(String referralId, String declineReason) {
        PortalReferrals referral = load(referralId);
        Instant now = clock.instant();

        referral.setStatus(Statuses.PORTAL_REFERRAL_DECLINED);
        referral.setDeclineReason(declineReason);
        referral.setSurplusLinesAdvised(true);
        persistence.run(Update.entity(PortalReferrals_.class).entry(referral));

        addTimelineEvent(referralId, "Declined",
                declineReason + " (class code " + referral.getClassCode() + ") — "
                        + Statuses.SURPLUS_LINES_ADVICE, now);
        return load(referralId);
    }

    /**
     * "Referral service level is tracked and escalated" — a referral not
     * acknowledged within the service level for its tier escalates to the
     * underwriting manager, and the producer sees the escalation on the
     * submission timeline.
     *
     * The clock is paused while a referral awaits information, so those are
     * skipped.
     */
    public List<PortalReferrals> runSlaMonitor() {
        Instant now = clock.instant();
        List<PortalReferrals> escalated = new ArrayList<>();

        for (PortalReferrals referral : persistence.run(Select.from(PortalReferrals_.class))
                .listOf(PortalReferrals.class)) {
            if (!Statuses.PORTAL_REFERRAL_REFERRED.equals(referral.getStatus())) {
                continue;
            }
            if (referral.getAcknowledgedAt() != null
                    || referral.getAcknowledgementDueAt() == null
                    || now.isBefore(referral.getAcknowledgementDueAt())) {
                continue;
            }

            referral.setStatus(Statuses.PORTAL_REFERRAL_ESCALATED);
            referral.setEscalatedTo(Statuses.ESCALATION_UNDERWRITING_MANAGER);
            referral.setEscalatedAt(now);
            persistence.run(Update.entity(PortalReferrals_.class).entry(referral));

            addTimelineEvent(referral.getId(), "Escalated",
                    "Acknowledgement SLA missed — escalated to the "
                            + Statuses.ESCALATION_UNDERWRITING_MANAGER, now);
            escalated.add(load(referral.getId()));
        }
        return escalated;
    }

    /** The tier band containing the premium; see the table in the spec. */
    public PortalReferralTiers tierFor(BigDecimal annualPremium) {
        return persistence.run(Select.from(PortalReferralTiers_.class))
                .listOf(PortalReferralTiers.class).stream()
                .filter(t -> t.getMinPremium() == null
                        || annualPremium.compareTo(t.getMinPremium()) >= 0)
                .filter(t -> t.getMaxPremium() == null
                        || annualPremium.compareTo(t.getMaxPremium()) <= 0)
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.SERVER_ERROR,
                        "No referral tier band covers " + annualPremium));
    }

    /** Timeline the producer sees on the submission. */
    public List<ReferralTimelineEvents> timelineOf(String referralId) {
        return persistence.run(Select.from(ReferralTimelineEvents_.class)
                .where(e -> e.referral_ID().eq(referralId)))
                .listOf(ReferralTimelineEvents.class);
    }

    /**
     * The acknowledgement deadline: business hours for the Standard and Complex
     * tiers, whole business days for Large account.
     */
    private Instant acknowledgementDueAt(Instant referredAt, PortalReferralTiers tier) {
        LocalDateTime start = local(referredAt);
        if (tier.getAcknowledgementHours() != null) {
            return instant(BusinessHours.plusBusinessHours(start,
                    tier.getAcknowledgementHours().doubleValue()));
        }
        int days = tier.getAcknowledgementDays() == null ? 1 : tier.getAcknowledgementDays();
        return instant(BusinessHours.plusBusinessHours(start,
                (double) days * BusinessHours.HOURS_PER_DAY));
    }

    private void addTimelineEvent(String referralId, String eventType, String description,
            Instant occurredAt) {
        ReferralTimelineEvents event = ReferralTimelineEvents.create();
        event.setId(UUID.randomUUID().toString());
        event.setReferralId(referralId);
        event.setEventType(eventType);
        event.setDescription(description);
        event.setOccurredAt(occurredAt);
        event.setVisibleToProducer(true);
        persistence.run(Insert.into(ReferralTimelineEvents_.class).entry(event));
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, zone());
    }

    private Instant instant(LocalDateTime local) {
        return local.atZone(zone()).toInstant();
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    public PortalReferrals load(String referralId) {
        return persistence.run(Select.from(PortalReferrals_.class)
                .where(r -> r.ID().eq(referralId)))
                .first(PortalReferrals.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Portal referral not found: " + referralId));
    }
}
