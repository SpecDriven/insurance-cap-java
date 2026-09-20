package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.PortalQuotes;
import cds.gen.insurance.PortalReferralTiers;
import cds.gen.insurance.PortalReferrals;
import cds.gen.insurance.Producers;
import cds.gen.insurance.ReferralTimelineEvents;

/**
 * Spec: specs/distribution/agent-portal/hand-off-quote-to-underwriter.feature.md
 * "Producer withdraws a pending referral" and "Underwriter counteroffer returns
 * to the producer" are [proposed] and intentionally absent.
 */
@DisplayName("Hand Off a Quote to an Underwriter — specs/distribution/agent-portal/hand-off-quote-to-underwriter.feature.md")
class HandOffQuoteToUnderwriterTest extends DistributionTestBase {

    /** 20 January 2026 is a Tuesday. */
    private static final LocalDate TUESDAY = LocalDate.of(2026, 1, 20);

    @Test
    @DisplayName("Premium above binding authority is referred @v1")
    void premiumAboveBindingAuthorityIsReferred() {
        // A producer with $25,000 binding authority per policy
        Producers producer = producerWithAuthority("6600101", new BigDecimal("25000.00"));
        PortalQuotes quote = portalQuoteFor(producer);

        // A commercial auto quote rates at $38,400 annual premium
        PortalReferrals referral = db(() -> portalReferrals.handOff(quote.getId(),
                producer.getId(), new BigDecimal("38400.00"), "CA-7219"));

        // The quote is referred to an underwriter rather than bound
        assertThat(referral.getStatus()).isEqualTo(Statuses.PORTAL_REFERRAL_REFERRED);

        // The referral reason "exceeds producer binding authority" is attached
        assertThat(referral.getReason())
                .isEqualTo(Statuses.REFERRAL_EXCEEDS_BINDING_AUTHORITY);
    }

    @Test
    @DisplayName("Referral service level is tracked and escalated @v1")
    void referralServiceLevelIsTrackedAndEscalated() {
        // A referral submitted at 09:00 on a Tuesday
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(9, 0));
        Producers producer = producerWithAuthority("6600102", new BigDecimal("25000.00"));
        PortalQuotes quote = portalQuoteFor(producer);

        // $38,400 is under $50,000, so this is the Standard tier: a 4 business
        // hour acknowledgement SLA (see the table in the spec)
        PortalReferrals referral = db(() -> portalReferrals.handOff(quote.getId(),
                producer.getId(), new BigDecimal("38400.00"), "CA-7219"));

        assertThat(referral.getTierName()).isEqualTo(Statuses.TIER_STANDARD);

        // Inside the SLA nothing escalates
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(12, 0));
        db(() -> portalReferrals.runSlaMonitor());
        assertThat(db(() -> portalReferrals.load(referral.getId())).getStatus())
                .isEqualTo(Statuses.PORTAL_REFERRAL_REFERRED);

        // The referral is not acknowledged within the service level for its tier
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(14, 0));
        List<PortalReferrals> escalated = db(() -> portalReferrals.runSlaMonitor());
        clock.setBusinessDate(BASE_DATE);

        // The referral escalates to the underwriting manager
        assertThat(escalated).extracting(PortalReferrals::getId).contains(referral.getId());
        PortalReferrals reloaded = db(() -> portalReferrals.load(referral.getId()));
        assertThat(reloaded.getStatus()).isEqualTo(Statuses.PORTAL_REFERRAL_ESCALATED);
        assertThat(reloaded.getEscalatedTo())
                .isEqualTo(Statuses.ESCALATION_UNDERWRITING_MANAGER);

        // The producer sees the escalation on the submission timeline
        assertThat(db(() -> portalReferrals.timelineOf(referral.getId())))
                .extracting(ReferralTimelineEvents::getEventType)
                .contains("Escalated");
        assertThat(db(() -> portalReferrals.timelineOf(referral.getId())))
                .filteredOn(e -> "Escalated".equals(e.getEventType()))
                .allSatisfy(e -> assertThat(e.getVisibleToProducer()).isTrue());
    }

    @Test
    @DisplayName("Referral service level is tracked and escalated @v1 — tiers follow the premium bands")
    void referralTiersFollowThePremiumBands() {
        // The table in the spec: Standard under $50,000, Complex $50,000 –
        // $250,000, Large account over $250,000
        PortalReferralTiers standard =
                db(() -> portalReferrals.tierFor(new BigDecimal("38400.00")));
        assertThat(standard.getTierName()).isEqualTo(Statuses.TIER_STANDARD);
        assertThat(standard.getAcknowledgementHours()).isEqualByComparingTo("4.00");
        assertThat(standard.getDecisionDays()).isEqualTo(2);

        PortalReferralTiers complex =
                db(() -> portalReferrals.tierFor(new BigDecimal("120000.00")));
        assertThat(complex.getTierName()).isEqualTo(Statuses.TIER_COMPLEX);
        assertThat(complex.getAcknowledgementHours()).isEqualByComparingTo("8.00");
        assertThat(complex.getDecisionDays()).isEqualTo(5);

        PortalReferralTiers large =
                db(() -> portalReferrals.tierFor(new BigDecimal("400000.00")));
        assertThat(large.getTierName()).isEqualTo(Statuses.TIER_LARGE_ACCOUNT);
        assertThat(large.getAcknowledgementDays()).isEqualTo(1);
        assertThat(large.getDecisionDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("Missing loss runs stop the underwriting clock @v1")
    void missingLossRunsStopTheUnderwritingClock() {
        // A commercial submission requiring 5 years of loss runs
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(9, 0));
        Producers producer = producerWithAuthority("6600103", new BigDecimal("25000.00"));
        PortalQuotes quote = portalQuoteFor(producer);
        PortalReferrals referral = db(() -> portalReferrals.handOff(quote.getId(),
                producer.getId(), new BigDecimal("38400.00"), "CA-7219"));
        var originalDue = referral.getAcknowledgementDueAt();

        // The underwriter requests the missing loss runs
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(10, 0));
        PortalReferrals paused = db(() -> portalReferrals.requestLossRuns(referral.getId(), 5));

        // The submission moves to awaiting-information and the decision clock pauses
        assertThat(paused.getStatus()).isEqualTo(Statuses.PORTAL_REFERRAL_AWAITING_INFO);
        assertThat(paused.getClockPausedAt()).isNotNull();

        // While paused, the SLA monitor does not escalate it even past the deadline
        clock.setBusinessDateTime(TUESDAY, LocalTime.of(15, 0));
        db(() -> portalReferrals.runSlaMonitor());
        assertThat(db(() -> portalReferrals.load(referral.getId())).getStatus())
                .isEqualTo(Statuses.PORTAL_REFERRAL_AWAITING_INFO);

        // The clock resumes when the producer uploads the loss runs
        PortalReferrals resumed = db(() -> portalReferrals.uploadLossRuns(referral.getId()));
        clock.setBusinessDate(BASE_DATE);

        assertThat(resumed.getStatus()).isEqualTo(Statuses.PORTAL_REFERRAL_REFERRED);
        assertThat(resumed.getClockResumedAt()).isNotNull();

        // The 5 business hours spent awaiting information did not count against
        // the acknowledgement deadline
        assertThat(resumed.getPausedHours()).isEqualByComparingTo("5.00");
        assertThat(resumed.getAcknowledgementDueAt()).isAfter(originalDue);
    }

    @Test
    @DisplayName("Declined submission records an appetite reason @v1")
    void declinedSubmissionRecordsAnAppetiteReason() {
        // A referred submission for a roofing contractor class code
        Producers producer = producerWithAuthority("6600104", new BigDecimal("25000.00"));
        PortalQuotes quote = portalQuoteFor(producer);
        PortalReferrals referral = db(() -> portalReferrals.handOff(quote.getId(),
                producer.getId(), new BigDecimal("38400.00"), "5551-roofing"));

        // The underwriter declines on appetite grounds
        PortalReferrals declined = db(() -> portalReferrals.decline(referral.getId(),
                Statuses.DECLINE_OUTSIDE_APPETITE));

        // The decline reason and class code are recorded on the submission
        assertThat(declined.getStatus()).isEqualTo(Statuses.PORTAL_REFERRAL_DECLINED);
        assertThat(declined.getDeclineReason()).isEqualTo(Statuses.DECLINE_OUTSIDE_APPETITE);
        assertThat(declined.getClassCode()).isEqualTo("5551-roofing");

        // The producer is advised the risk may be placed through surplus lines
        assertThat(declined.getSurplusLinesAdvised()).isTrue();
        assertThat(db(() -> portalReferrals.timelineOf(referral.getId())))
                .filteredOn(e -> "Declined".equals(e.getEventType()))
                .anySatisfy(e -> {
                    assertThat(e.getDescription()).contains("5551-roofing");
                    assertThat(e.getDescription()).contains(Statuses.SURPLUS_LINES_ADVICE);
                });
    }

    /** A producer at an appointed agency, with the given per-policy authority. */
    private Producers producerWithAuthority(String npn, BigDecimal authority) {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), npn, "OH");
        return db(() -> {
            Producers loaded = licensing.load(producer.getId());
            loaded.setBindingAuthority(authority);
            persistence.run(com.sap.cds.ql.Update
                    .entity(cds.gen.insurance.Producers_.class).entry(loaded));
            return loaded;
        });
    }

    private PortalQuotes portalQuoteFor(Producers producer) {
        return db(() -> portalQuoting.startAutoQuote(producer.getId(), "Ford", "Transit",
                2022, "Fleet Services LLC", "fleet@example.com", "+1-555-0188",
                "OH", "43215", true));
    }
}
