package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.AgencyAppointments;
import cds.gen.insurance.PortalUsers;

/**
 * Spec: specs/distribution/agency-management/manage-agency-appointment.feature.md
 * "Agency may not bind a line outside its appointment" and "Rejected DOI filing
 * is surfaced for correction" are [proposed] and intentionally absent.
 */
@DisplayName("Manage Agency Appointment — specs/distribution/agency-management/manage-agency-appointment.feature.md")
class ManageAgencyAppointmentTest extends DistributionTestBase {

    @Test
    @DisplayName("Appointment is filed with the state department of insurance @v1")
    void appointmentIsFiledWithTheStateDepartmentOfInsurance() {
        // An approved agency with an Ohio resident agency license
        Agencies agency = newAgency("OH", null);

        // The distribution manager appoints the agency for personal auto and homeowners
        List<AgencyAppointments> created = db(() -> appointments.appoint(agency.getId(), "OH",
                List.of(Statuses.LINE_PERSONAL_AUTO, Statuses.LINE_HOMEOWNERS)));

        // An appointment filing is transmitted to the Ohio DOI
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(a -> {
            assertThat(a.getState()).isEqualTo("OH");
            assertThat(a.getFilingTransmittedOn()).isEqualTo(BASE_DATE);
            assertThat(a.getStatus()).isEqualTo(Statuses.APPOINTMENT_PENDING);
            // Not yet effective — the DOI has not acknowledged it
            assertThat(a.getEffectiveDate()).isNull();
        });

        // The appointment becomes effective on the date the DOI acknowledges the filing
        LocalDate acknowledgedOn = BASE_DATE.plusDays(9);
        AgencyAppointments acknowledged = db(() -> appointments
                .acknowledge(created.get(0).getId(), acknowledgedOn));

        assertThat(acknowledged.getStatus()).isEqualTo(Statuses.APPOINTMENT_ACTIVE);
        assertThat(acknowledged.getAcknowledgedOn()).isEqualTo(acknowledgedOn);
        assertThat(acknowledged.getEffectiveDate()).isEqualTo(acknowledgedOn);
    }

    @Test
    @DisplayName("Adding a state expands the existing appointment @v1")
    void addingAStateExpandsTheExistingAppointment() {
        // An agency appointed in Ohio and Indiana
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> {
            var indiana = appointments.appoint(agency.getId(), "IN",
                    List.of(Statuses.LINE_PERSONAL_AUTO));
            indiana.forEach(a -> appointments.acknowledge(a.getId(), BASE_DATE.minusMonths(3)));
            return indiana;
        });

        // The agency requests an additional appointment in Tennessee for homeowners
        List<AgencyAppointments> tennessee = db(() -> appointments.appoint(agency.getId(), "TN",
                List.of(Statuses.LINE_HOMEOWNERS)));

        // A Tennessee filing is submitted
        assertThat(tennessee).hasSize(1);
        assertThat(tennessee.get(0).getFilingTransmittedOn()).isEqualTo(BASE_DATE);

        // Without disturbing the existing appointments
        assertThat(db(() -> appointments.activeFor(agency.getId(), "OH"))).hasSize(1);
        assertThat(db(() -> appointments.activeFor(agency.getId(), "IN"))).hasSize(1);

        // The agency code gains Tennessee homeowners authority once acknowledged
        assertThat(db(() -> appointments.activeFor(agency.getId(), "TN"))).isEmpty();
        db(() -> appointments.acknowledge(tennessee.get(0).getId(), BASE_DATE.plusDays(11)));

        var tnActive = db(() -> appointments.activeFor(agency.getId(), "TN"));
        assertThat(tnActive).hasSize(1);
        assertThat(tnActive.get(0).getLineOfBusiness()).isEqualTo(Statuses.LINE_HOMEOWNERS);
    }

    @Test
    @DisplayName("Non-resident appointment requires a countersignature arrangement @v1")
    void nonResidentAppointmentRequiresACountersignatureArrangement() {
        // An agency domiciled in Ohio seeking a Kentucky appointment, where
        // Kentucky requires a resident countersignature for commercial lines
        Agencies agency = newAgency("OH", null);

        // The Kentucky appointment is requested for both personal and commercial lines
        List<AgencyAppointments> kentucky = db(() -> appointments.appoint(agency.getId(), "KY",
                List.of(Statuses.LINE_PERSONAL_AUTO, Statuses.LINE_COMMERCIAL_GL)));

        AgencyAppointments personal = kentucky.stream()
                .filter(a -> Statuses.LINE_PERSONAL_AUTO.equals(a.getLineOfBusiness()))
                .findFirst().orElseThrow();
        AgencyAppointments commercial = kentucky.stream()
                .filter(a -> Statuses.LINE_COMMERCIAL_GL.equals(a.getLineOfBusiness()))
                .findFirst().orElseThrow();

        // The appointment is issued for personal lines only
        assertThat(personal.getStatus()).isEqualTo(Statuses.APPOINTMENT_PENDING);
        assertThat(personal.getFilingTransmittedOn()).isEqualTo(BASE_DATE);
        assertThat(personal.getNonResident()).isTrue();

        // The commercial lines request is queued pending a countersignature arrangement
        assertThat(commercial.getStatus())
                .isEqualTo(Statuses.APPOINTMENT_PENDING_COUNTERSIGNATURE);
        assertThat(commercial.getCountersignatureRequired()).isTrue();
        assertThat(commercial.getFilingTransmittedOn()).isNull();
    }

    @Test
    @DisplayName("Appointment lapses when the agency license expires @v1")
    void appointmentLapsesWhenTheAgencyLicenseExpires() {
        // An appointed agency whose agency license expires on 31 March 2026
        LocalDate expiry = LocalDate.of(2026, 3, 31);
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        db(() -> {
            Agencies loaded = onboarding.load(agency.getId());
            loaded.setAgencyLicenseExpiresOn(expiry);
            persistence.run(com.sap.cds.ql.Update.entity(cds.gen.insurance.Agencies_.class)
                    .entry(loaded));
            return loaded;
        });
        PortalUsers user = insertPortalUser(agency.getId(), null, Statuses.ROLE_PRODUCER,
                Statuses.PORTAL_ACTIVE, BASE_DATE.minusDays(2));

        // An in-force policy the agency services
        var policy = insertAgencyPolicy(agency.getId(), null,
                new java.math.BigDecimal("612.00"), BASE_DATE.minusMonths(1), null);

        try {
            // 1 April 2026 arrives with no renewal on file
            clock.setBusinessDate(LocalDate.of(2026, 4, 1));
            List<AgencyAppointments> lapsed = db(() -> appointments.lapseExpired());

            // The appointment is moved to lapsed status
            assertThat(lapsed).isNotEmpty();
            assertThat(lapsed).allSatisfy(a -> {
                assertThat(a.getStatus()).isEqualTo(Statuses.APPOINTMENT_LAPSED);
                assertThat(a.getLapsedOn()).isEqualTo(LocalDate.of(2026, 4, 1));
            });

            // New business submissions from the agency are suspended
            PortalUsers suspended = db(() -> portalAccess.load(user.getId()));
            assertThat(suspended.getNewBusinessSuspended()).isTrue();

            // In-force policies remain serviceable for renewal processing
            assertThat(suspended.getServicingRetained()).isTrue();
            assertThat(db(() -> reloadPolicy(policy.getId())).getStatus())
                    .isEqualTo(Statuses.POLICY_IN_FORCE);
        } finally {
            clock.setBusinessDate(BASE_DATE);
            deletePolicies(policy.getId());
        }
    }
}
