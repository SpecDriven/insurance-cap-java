package com.acme.insurance.underwriting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.BusinessHours;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.UnderwritingTestBase;

import cds.gen.insurance.Referrals;
import cds.gen.insurance.Submissions;

/**
 * Spec: specs/underwriting/referrals/apply-referral-sla.feature.md
 * "Reassignment does not reset the clock" and "Reminder sent before the target
 * is reached" are [proposed] and intentionally absent.
 *
 * Business hours are 9:00 AM to 5:00 PM in the operating timezone; weekend and
 * holiday hours are not counted (see {@link BusinessHours}).
 */
@DisplayName("Apply Underwriting Referral SLA — specs/underwriting/referrals/apply-referral-sla.feature.md")
class ApplyReferralSlaTest extends UnderwritingTestBase {

    /** Monday 12 January 2026 — a plain business week. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 1, 12);
    /** Friday 3 July 2026 falls before the observed Independence Day weekend. */
    private static final LocalDate FRIDAY_BEFORE_HOLIDAY = LocalDate.of(2026, 5, 22);

    @Test
    @DisplayName("Standard referral answered within the target @v1")
    void standardReferralAnsweredWithinTheTarget() {
        // A standard personal lines referral queued at 9:00 AM Monday
        clock.setBusinessDateTime(MONDAY, LocalTime.of(9, 0));
        Referrals referral = openStandardReferral();
        assertThat(referral.getSlaTargetHours()).isEqualTo(8);

        // The underwriter records a decision at 2:00 PM the same day
        clock.setBusinessDateTime(MONDAY, LocalTime.of(14, 0));
        db(() -> decisions.record(referral.getId(), Statuses.DECISION_ACCEPT, "Dana Whitfield",
                null, "Clean file", null, null, null, null));

        // The referral is marked met against its 8 business hour target
        assertThat(db(() -> referrals.load(referral.getId())).getSlaStatus())
                .isEqualTo(Statuses.SLA_MET);
    }

    @Test
    @DisplayName("Breach escalates to the team lead @v1")
    void breachEscalatesToTheTeamLead() {
        // A standard referral that passes 8 business hours without a decision
        clock.setBusinessDateTime(MONDAY, LocalTime.of(9, 0));
        Referrals referral = openStandardReferral();

        // 9:00 Monday + 8 business hours = 5:00 PM Monday; move just past it
        clock.setBusinessDateTime(MONDAY.plusDays(1), LocalTime.of(9, 30));
        db(() -> referrals.runSlaMonitor());

        Referrals breached = db(() -> referrals.load(referral.getId()));

        // The referral is escalated to the team lead and flagged as breached
        assertThat(breached.getSlaStatus()).isEqualTo(Statuses.SLA_BREACHED);
        assertThat(breached.getEscalatedTo()).isEqualTo("team lead");

        // The agent receives a status update with a revised expected decision time
        assertThat(emailSubjects()).contains("Underwriting referral update");
    }

    @Test
    @DisplayName("Clock pauses while awaiting agent information @v1")
    void clockPausesWhileAwaitingAgentInformation() {
        clock.setBusinessDateTime(MONDAY, LocalTime.of(9, 0));
        Referrals referral = openStandardReferral();

        // The underwriter requests a roof inspection report from the agent
        clock.setBusinessDateTime(MONDAY, LocalTime.of(11, 0));
        db(() -> referrals.awaitAgentInformation(referral.getId()));

        // The referral sits in "awaiting agent" status for 3 business days
        clock.setBusinessDateTime(MONDAY.plusDays(3), LocalTime.of(11, 0));

        // The SLA clock is suspended for that period: the monitor does not breach it
        db(() -> referrals.runSlaMonitor());
        assertThat(db(() -> referrals.load(referral.getId())).getSlaStatus())
                .isEqualTo(Statuses.SLA_OPEN);

        // The clock resumes when the requested document is uploaded
        Referrals resumed = db(() -> referrals.resumeClock(referral.getId()));
        assertThat(resumed.getSlaPausedHours()).isNotNull();
        assertThat(resumed.getSlaPausedHours().doubleValue()).isGreaterThan(0d);
        assertThat(resumed.getStatus()).isNotEqualTo(Statuses.REFERRAL_AWAITING_AGENT);

        // Only the 2 hours before the pause count against the 8-hour target
        assertThat(db(() -> referrals.elapsedBusinessHours(
                referrals.load(referral.getId())))).isLessThan(8d);
    }

    @Test
    @DisplayName("Weekend and holiday hours are not counted @v1")
    void weekendAndHolidayHoursAreNotCounted() {
        // Memorial Day 2026 falls on Monday 25 May; queue at 4:00 PM the Friday before
        assertThat(BusinessHours.isHoliday(LocalDate.of(2026, 5, 25))).isTrue();
        clock.setBusinessDateTime(FRIDAY_BEFORE_HOLIDAY, LocalTime.of(16, 0));
        Referrals referral = openStandardReferral();

        // Saturday, Sunday, and the Monday holiday contribute no business hours:
        // only the last hour of Friday has elapsed by Tuesday morning.
        clock.setBusinessDateTime(LocalDate.of(2026, 5, 26), LocalTime.of(9, 0));
        assertThat(db(() -> referrals.elapsedBusinessHours(referrals.load(referral.getId()))))
                .isEqualTo(1d);

        // Only business hours from Tuesday morning count toward the target, so the
        // referral is still open rather than breached.
        db(() -> referrals.runSlaMonitor());
        assertThat(db(() -> referrals.load(referral.getId())).getSlaStatus())
                .isEqualTo(Statuses.SLA_OPEN);
    }

    private Referrals openStandardReferral() {
        Submissions submission = newHomeSubmission();
        db(() -> eligibility.screen(submission.getId(),
                EligibilityManager.ruleOutcomes(true, false, true, true, true)));
        return db(() -> referrals.refer(submission.getId(), Statuses.REFERRAL_TYPE_STANDARD,
                "Texas property"));
    }
}
