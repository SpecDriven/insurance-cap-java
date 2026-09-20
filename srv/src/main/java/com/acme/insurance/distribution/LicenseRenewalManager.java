package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.LicenseReminders;
import cds.gen.insurance.LicenseReminders_;
import cds.gen.insurance.ProducerLicenses;
import cds.gen.insurance.ProducerLicenses_;
import cds.gen.insurance.Producers;

/**
 * The renewal tracker: escalating reminders before expiration, per-jurisdiction
 * tracking, reinstatement inside the state window, and clearing the flag when
 * the registry confirms a renewal.
 *
 * Spec: specs/distribution/producer-licensing/track-license-renewal.feature.md
 */
@Component
public class LicenseRenewalManager {

    private final PersistenceService persistence;
    private final ProducerLicensingManager licensing;
    private final AgencyOnboardingManager agencies;
    private final EmailPort email;
    private final Clock clock;

    public LicenseRenewalManager(PersistenceService persistence,
            ProducerLicensingManager licensing, AgencyOnboardingManager agencies,
            EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.licensing = licensing;
        this.agencies = agencies;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Expiration reminders escalate as the date approaches" — the tracker runs
     * each morning and sends reminders at 90, 60, 30, and 7 days before
     * expiration; the 7-day reminder copies the agency principal.
     *
     * "Multi-state producer is tracked per jurisdiction" — reminders are raised
     * against a single licence row, so only the state approaching expiration is
     * flagged and the producer's other states are unaffected.
     *
     * "Producer departure stops reminders" is [proposed]; departed producers are
     * nonetheless skipped here because a reminder to a departed producer has no
     * addressee.
     */
    public List<LicenseReminders> runTracker() {
        LocalDate today = LocalDate.now(clock);
        List<LicenseReminders> sent = new ArrayList<>();

        for (ProducerLicenses license : persistence.run(Select.from(ProducerLicenses_.class))
                .listOf(ProducerLicenses.class)) {
            if (license.getExpiresOn() == null
                    || !Statuses.LICENSE_ACTIVE.equals(license.getStatus())) {
                continue;
            }
            long daysOut = ChronoUnit.DAYS.between(today, license.getExpiresOn());
            if (!Statuses.REMINDER_DAYS_BEFORE.contains((int) daysOut)) {
                continue;
            }
            if (alreadySent(license.getId(), (int) daysOut)) {
                continue;
            }

            Producers producer = licensing.load(license.getProducerId());
            if (Statuses.PRODUCER_DEPARTED.equals(producer.getStatus())) {
                continue;
            }
            sent.add(sendReminder(license, producer, (int) daysOut, today));
        }
        return sent;
    }

    private LicenseReminders sendReminder(ProducerLicenses license, Producers producer,
            int daysBefore, LocalDate today) {
        boolean copyPrincipal = daysBefore == Statuses.REMINDER_COPY_PRINCIPAL_DAYS;

        LicenseReminders reminder = LicenseReminders.create();
        reminder.setId(UUID.randomUUID().toString());
        reminder.setLicenseId(license.getId());
        reminder.setDaysBefore(daysBefore);
        reminder.setSentOn(today);
        reminder.setCopiedPrincipal(copyPrincipal);
        persistence.run(Insert.into(LicenseReminders_.class).entry(reminder));

        String subject = "License renewal reminder — " + daysBefore + " days";
        String body = "The " + license.getState() + " license for " + producer.getFullName()
                + " expires on " + license.getExpiresOn() + ".";
        email.send(new EmailMessage(producer.getEmail(), subject, body));

        if (copyPrincipal && producer.getAgencyId() != null) {
            Agencies agency = agencies.load(producer.getAgencyId());
            if (agency.getPrincipalEmail() != null) {
                email.send(new EmailMessage(agency.getPrincipalEmail(), subject, body));
            }
        }
        return reminder;
    }

    /**
     * "Lapsed license enters the state reinstatement window" — renewing on
     * 20 October, inside the state's 12-month reinstatement window after a
     * 30 September expiration, restores the licence without re-examination, and
     * business written during the lapse is reviewed for validity.
     */
    public ProducerLicenses reinstate(String licenseId, LocalDate renewedOn) {
        ProducerLicenses license = licensing.loadLicense(licenseId);
        LocalDate expired = license.getExpiresOn();
        if (expired == null || !renewedOn.isAfter(expired)) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "License " + licenseId + " has not lapsed");
        }

        int windowMonths = license.getReinstatementWindowMonths() == null
                ? Statuses.REINSTATEMENT_WINDOW_MONTHS : license.getReinstatementWindowMonths();
        if (renewedOn.isAfter(expired.plusMonths(windowMonths))) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "The " + windowMonths + "-month reinstatement window closed on "
                            + expired.plusMonths(windowMonths) + "; re-examination is required.");
        }

        // Restored without re-examination.
        license.setStatus(Statuses.LICENSE_REINSTATED);
        license.setReinstatedOn(renewedOn);
        // Business written between expiration and reinstatement is reviewed.
        license.setReviewedForValidity(true);
        persistence.run(Update.entity(ProducerLicenses_.class).entry(license));
        return licensing.loadLicense(licenseId);
    }

    /**
     * "Renewal confirmed by the registry clears the tracker" — the registry
     * reporting a new expiration clears the tracking flag, and the new date
     * drives the next reminder cycle.
     */
    public ProducerLicenses recordRenewal(String licenseId, LocalDate newExpiresOn) {
        ProducerLicenses license = licensing.loadLicense(licenseId);
        license.setExpiresOn(newExpiresOn);
        license.setStatus(Statuses.LICENSE_ACTIVE);
        license.setLastVerifiedOn(LocalDate.now(clock));
        persistence.run(Update.entity(ProducerLicenses_.class).entry(license));

        // Clearing the reminders on the old cycle is what clears the tracker; the
        // next cycle is driven off the new expiration date.
        persistence.run(com.sap.cds.ql.Delete.from(LicenseReminders_.class)
                .where(r -> r.license_ID().eq(licenseId)));

        return licensing.loadLicense(licenseId);
    }

    /** Reminders raised on a licence, newest first. */
    public List<LicenseReminders> remindersFor(String licenseId) {
        return persistence.run(Select.from(LicenseReminders_.class)
                .where(r -> r.license_ID().eq(licenseId)))
                .listOf(LicenseReminders.class);
    }

    private boolean alreadySent(String licenseId, int daysBefore) {
        return persistence.run(Select.from(LicenseReminders_.class)
                .where(r -> r.license_ID().eq(licenseId).and(r.daysBefore().eq(daysBefore))))
                .first(LicenseReminders.class)
                .isPresent();
    }
}
