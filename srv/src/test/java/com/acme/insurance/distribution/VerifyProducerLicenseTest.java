package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.ProducerLicenses;
import cds.gen.insurance.Producers;

/**
 * Spec: specs/distribution/producer-licensing/verify-producer-license.feature.md
 * "Appointment and license must both be active", "Missing line of authority
 * blocks a commercial submission", and "Registry outage falls back to the last
 * good verification" are [proposed] and intentionally absent.
 */
@DisplayName("Verify Producer License — specs/distribution/producer-licensing/verify-producer-license.feature.md")
class VerifyProducerLicenseTest extends DistributionTestBase {

    @Test
    @DisplayName("Producer license verified against the national registry @v1")
    void producerLicenseVerifiedAgainstTheNationalRegistry() {
        // A producer with national producer number 8841207
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("8841207", "Roberto Alvarez",
                "roberto.alvarez@buckeye.example", agency.getId(), "OH",
                Statuses.ROLE_PRODUCER));

        // The licensing service queries the national registry
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Roberto Alvarez", Statuses.LICENSE_CLASS_PC, BASE_DATE.plusYears(1)));

        // The producer's Ohio property and casualty license is confirmed active
        assertThat(license.getState()).isEqualTo("OH");
        assertThat(license.getLicenseClass()).isEqualTo(Statuses.LICENSE_CLASS_PC);
        assertThat(license.getStatus()).isEqualTo(Statuses.LICENSE_ACTIVE);

        // The verification result is stamped with the query date
        assertThat(license.getLastVerifiedOn()).isEqualTo(BASE_DATE);
        assertThat(license.getVerificationSource()).isEqualTo(Statuses.REGISTRY_NATIONAL);
    }

    @Test
    @DisplayName("Name mismatch is routed for manual reconciliation @v1")
    void nameMismatchIsRoutedForManualReconciliation() {
        // A producer recorded as "R. Alvarez" in the agency record
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = db(() -> licensing.register("8841208", "R. Alvarez",
                "r.alvarez@buckeye.example", agency.getId(), "OH", Statuses.ROLE_PRODUCER));

        // The registry returns "Roberto Alvarez-Mendez" for the same NPN, and the
        // nightly verification runs
        ProducerLicenses license = db(() -> licensing.verify(producer.getId(), "OH",
                "Roberto Alvarez-Mendez", Statuses.LICENSE_CLASS_PC, BASE_DATE.plusYears(1)));

        // The record is flagged for manual reconciliation rather than failed
        assertThat(license.getNameMismatch()).isTrue();
        assertThat(license.getNameOnRegistry()).isEqualTo("Roberto Alvarez-Mendez");
        assertThat(license.getStatus()).isEqualTo(Statuses.LICENSE_ACTIVE);

        // Production continues for 30 days while the mismatch is resolved
        assertThat(license.getMismatchGraceDays()).isEqualTo(30);
        assertThat(db(() -> licensing.isAuthorized(producer.getId(), "OH"))).isTrue();
    }

    @Test
    @DisplayName("Non-resident license is required for out-of-state risks @v1")
    void nonResidentLicenseIsRequiredForOutOfStateRisks() {
        // A producer resident in Ohio submitting a risk garaged in Kentucky
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "8841209", "OH");

        // The licensing check runs on the state of the risk: the Ohio resident
        // license alone is not sufficient
        assertThatThrownBy(() -> db(() -> licensing.checkForRisk(producer.getId(), "KY")))
                .hasMessageContaining("active KY non-resident license is required")
                .hasMessageContaining("OH resident license alone is not sufficient");

        // An active Kentucky non-resident license is required
        ProducerLicenses kentucky = db(() -> licensing.verify(producer.getId(), "KY",
                "Roberto Alvarez", Statuses.LICENSE_CLASS_PC, BASE_DATE.plusYears(1)));
        assertThat(kentucky.getNonResident()).isTrue();

        assertThat(db(() -> licensing.checkForRisk(producer.getId(), "KY")).getStatus())
                .isEqualTo(Statuses.LICENSE_ACTIVE);
    }

    @Test
    @DisplayName("Suspended license halts binding the same day @v1")
    void suspendedLicenseHaltsBindingTheSameDay() {
        // A producer whose Tennessee license is suspended by the DOI on 14 May 2026
        Agencies agency = newAppointedAgency("TN", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "8841210", "TN");
        LocalDate suspendedOn = LocalDate.of(2026, 5, 14);

        // The suspension appears in the nightly registry feed
        ProducerLicenses suspended = db(() -> licensing.recordSuspension(
                producer.getId(), "TN", suspendedOn));

        // The producer's binding authority is revoked effective 14 May 2026
        assertThat(suspended.getStatus()).isEqualTo(Statuses.LICENSE_SUSPENDED);
        assertThat(suspended.getSuspendedOn()).isEqualTo(suspendedOn);
        assertThat(db(() -> licensing.load(producer.getId())).getBindingRevokedOn())
                .isEqualTo(suspendedOn);
        assertThat(db(() -> licensing.isAuthorized(producer.getId(), "TN"))).isFalse();

        // The producer's agency principal is notified by email
        assertThat(emailSubjects()).contains("Producer license suspended");
        assertThat(emails.sent()).anySatisfy(message -> {
            assertThat(message.to()).isEqualTo("dana.whitfield@buckeye.example");
            assertThat(message.body()).contains(suspendedOn.toString());
        });
    }
}
