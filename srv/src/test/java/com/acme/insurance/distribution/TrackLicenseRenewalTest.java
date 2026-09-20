package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.LicenseReminders;
import cds.gen.insurance.ProducerLicenses;
import cds.gen.insurance.Producers;

/**
 * Spec: specs/distribution/producer-licensing/track-license-renewal.feature.md
 * "Continuing education shortfall is flagged before renewal" and "Producer
 * departure stops reminders" are [proposed] and intentionally absent.
 */
@DisplayName("Track License Renewal — specs/distribution/producer-licensing/track-license-renewal.feature.md")
class TrackLicenseRenewalTest extends DistributionTestBase {

    private static final LocalDate SEPT_30_2026 = LocalDate.of(2026, 9, 30);

    @Test
    @DisplayName("Expiration reminders escalate as the date approaches @v1")
    void expirationRemindersEscalateAsTheDateApproaches() {
        // A producer whose Ohio license expires 30 September 2026
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("9930100", "Aisha Bello",
                "aisha.bello@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, SEPT_30_2026));

        // The renewal tracker runs each morning; reminders are sent at 90, 60,
        // 30, and 7 days before expiration
        for (int daysBefore : List.of(90, 60, 30, 7)) {
            emails.reset();
            clock.setBusinessDate(SEPT_30_2026.minusDays(daysBefore));

            List<LicenseReminders> sent = db(() -> licenseRenewals.runTracker());

            assertThat(sent)
                    .as("reminder at %d days before expiration", daysBefore)
                    .extracting(LicenseReminders::getDaysBefore)
                    .contains(daysBefore);
            assertThat(emailSubjects())
                    .contains("License renewal reminder — " + daysBefore + " days");
        }
        clock.setBusinessDate(BASE_DATE);

        // Exactly four reminders, one per milestone
        assertThat(db(() -> licenseRenewals.remindersFor(license.getId())))
                .extracting(LicenseReminders::getDaysBefore)
                .containsExactlyInAnyOrder(90, 60, 30, 7);

        // The 7-day reminder copies the agency principal
        LicenseReminders sevenDay = db(() -> licenseRenewals.remindersFor(license.getId()))
                .stream().filter(r -> r.getDaysBefore() == 7).findFirst().orElseThrow();
        assertThat(sevenDay.getCopiedPrincipal()).isTrue();
    }

    @Test
    @DisplayName("Expiration reminders escalate as the date approaches @v1 — a quiet day sends nothing")
    void noReminderIsSentAwayFromTheMilestones() {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("9930101", "Sam Ito",
                "sam.ito@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Sam Ito", Statuses.LICENSE_CLASS_PC, SEPT_30_2026));

        // 45 days out is not a reminder milestone
        clock.setBusinessDate(SEPT_30_2026.minusDays(45));
        db(() -> licenseRenewals.runTracker());
        clock.setBusinessDate(BASE_DATE);

        assertThat(db(() -> licenseRenewals.remindersFor(license.getId()))).isEmpty();
    }

    @Test
    @DisplayName("Multi-state producer is tracked per jurisdiction @v1")
    void multiStateProducerIsTrackedPerJurisdiction() {
        // A producer licensed in Ohio, Kentucky, and Tennessee with different
        // expiration dates
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("9930102", "Aisha Bello",
                "aisha.bello@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));

        ProducerLicenses ohio = db(() -> licensing.verify(producer.getId(), "OH",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, LocalDate.of(2027, 3, 31)));
        ProducerLicenses kentucky = db(() -> licensing.verify(producer.getId(), "KY",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, SEPT_30_2026));
        ProducerLicenses tennessee = db(() -> licensing.verify(producer.getId(), "TN",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, LocalDate.of(2027, 6, 30)));

        // The Kentucky license approaches expiration
        clock.setBusinessDate(SEPT_30_2026.minusDays(30));
        List<LicenseReminders> sent = db(() -> licenseRenewals.runTracker());
        clock.setBusinessDate(BASE_DATE);

        // Only Kentucky production is at risk and only Kentucky is flagged. The
        // H2 database is shared across the run, so scope this to the licences of
        // this scenario's own producer.
        List<String> thisProducersLicenses =
                List.of(ohio.getId(), kentucky.getId(), tennessee.getId());
        assertThat(sent)
                .filteredOn(r -> thisProducersLicenses.contains(r.getLicenseId()))
                .extracting(LicenseReminders::getLicenseId)
                .containsExactly(kentucky.getId());

        // Ohio and Tennessee authority is unaffected
        assertThat(db(() -> licenseRenewals.remindersFor(ohio.getId()))).isEmpty();
        assertThat(db(() -> licenseRenewals.remindersFor(tennessee.getId()))).isEmpty();
        assertThat(db(() -> licensing.isAuthorized(producer.getId(), "OH"))).isTrue();
        assertThat(db(() -> licensing.isAuthorized(producer.getId(), "TN"))).isTrue();
    }

    @Test
    @DisplayName("Lapsed license enters the state reinstatement window @v1")
    void lapsedLicenseEntersTheStateReinstatementWindow() {
        // An Ohio license that expired 30 September 2026 without renewal
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("9930103", "Aisha Bello",
                "aisha.bello@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, SEPT_30_2026));

        // The producer renews on 20 October 2026, within the state's 12-month
        // reinstatement window
        LocalDate renewedOn = LocalDate.of(2026, 10, 20);
        clock.setBusinessDate(renewedOn);
        ProducerLicenses reinstated =
                db(() -> licenseRenewals.reinstate(license.getId(), renewedOn));
        clock.setBusinessDate(BASE_DATE);

        // The license is restored without re-examination
        assertThat(reinstated.getStatus()).isEqualTo(Statuses.LICENSE_REINSTATED);
        assertThat(reinstated.getReinstatedOn()).isEqualTo(renewedOn);
        assertThat(reinstated.getReinstatementWindowMonths()).isEqualTo(12);

        // Business written during the lapse is reviewed for validity
        assertThat(reinstated.getReviewedForValidity()).isTrue();
    }

    @Test
    @DisplayName("Renewal confirmed by the registry clears the tracker @v1")
    void renewalConfirmedByTheRegistryClearsTheTracker() {
        // A producer flagged as expiring within 30 days
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("9930104", "Aisha Bello",
                "aisha.bello@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Aisha Bello", Statuses.LICENSE_CLASS_PC, SEPT_30_2026));

        clock.setBusinessDate(SEPT_30_2026.minusDays(30));
        db(() -> licenseRenewals.runTracker());
        assertThat(db(() -> licenseRenewals.remindersFor(license.getId()))).isNotEmpty();

        // The registry reports a renewed license with a new expiration of
        // 30 September 2028
        LocalDate newExpiry = LocalDate.of(2028, 9, 30);
        ProducerLicenses renewed =
                db(() -> licenseRenewals.recordRenewal(license.getId(), newExpiry));

        // The tracking flag is cleared
        assertThat(db(() -> licenseRenewals.remindersFor(license.getId()))).isEmpty();
        assertThat(renewed.getStatus()).isEqualTo(Statuses.LICENSE_ACTIVE);

        // The new expiration date drives the next reminder cycle
        assertThat(renewed.getExpiresOn()).isEqualTo(newExpiry);
        clock.setBusinessDate(newExpiry.minusDays(90));
        List<LicenseReminders> nextCycle = db(() -> licenseRenewals.runTracker());
        clock.setBusinessDate(BASE_DATE);

        assertThat(nextCycle).extracting(LicenseReminders::getLicenseId)
                .contains(license.getId());
        assertThat(nextCycle).extracting(LicenseReminders::getDaysBefore).contains(90);
    }
}
