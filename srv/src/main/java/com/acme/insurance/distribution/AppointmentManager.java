package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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
import cds.gen.insurance.AgencyAppointments;
import cds.gen.insurance.AgencyAppointments_;
import cds.gen.insurance.PortalUsers;
import cds.gen.insurance.PortalUsers_;

/**
 * State appointments: DOI filings, acknowledgement, non-resident
 * countersignature arrangements, and lapse when the agency licence expires.
 *
 * Spec: specs/distribution/agency-management/manage-agency-appointment.feature.md
 */
@Component
public class AppointmentManager {

    private final PersistenceService persistence;
    private final AgencyOnboardingManager agencies;
    private final Clock clock;

    public AppointmentManager(PersistenceService persistence, AgencyOnboardingManager agencies,
            Clock clock) {
        this.persistence = persistence;
        this.agencies = agencies;
        this.clock = clock;
    }

    /**
     * "Appointment is filed with the state department of insurance" — appointing
     * an approved Ohio agency for personal auto and homeowners transmits a filing
     * to the Ohio DOI; the appointment becomes effective on the acknowledgement
     * date, so it is created pending with no effective date yet.
     *
     * "Adding a state expands the existing appointment" — a Tennessee request is
     * filed on its own without disturbing the Ohio and Indiana appointments,
     * because each state/line pair is its own appointment row.
     *
     * "Non-resident appointment requires a countersignature arrangement" — an
     * Ohio-domiciled agency seeking Kentucky gets personal lines issued and the
     * commercial lines request queued pending a countersignature arrangement.
     */
    public List<AgencyAppointments> appoint(String agencyId, String state,
            Collection<String> lines) {
        Agencies agency = agencies.load(agencyId);
        LocalDate today = LocalDate.now(clock);
        boolean nonResident = state != null && !state.equalsIgnoreCase(agency.getDomicileState());

        List<AgencyAppointments> created = new ArrayList<>();
        for (String line : lines) {
            AgencyAppointments existing = find(agencyId, state, line);
            if (existing != null) {
                created.add(existing);
                continue;
            }

            AgencyAppointments appointment = AgencyAppointments.create();
            appointment.setId(UUID.randomUUID().toString());
            appointment.setAgencyId(agencyId);
            appointment.setState(state);
            appointment.setLineOfBusiness(line);
            appointment.setNonResident(nonResident);

            if (nonResident && countersignatureRequired(state, line)) {
                // Queued pending a resident countersignature arrangement; no
                // filing is transmitted for this line yet.
                appointment.setStatus(Statuses.APPOINTMENT_PENDING_COUNTERSIGNATURE);
                appointment.setCountersignatureRequired(true);
            } else {
                appointment.setStatus(Statuses.APPOINTMENT_PENDING);
                appointment.setFilingTransmittedOn(today);
            }

            persistence.run(Insert.into(AgencyAppointments_.class).entry(appointment));
            created.add(load(appointment.getId()));
        }
        return created;
    }

    /**
     * "Appointment is filed with the state department of insurance" — the
     * appointment becomes effective on the date the DOI acknowledges the filing.
     */
    public AgencyAppointments acknowledge(String appointmentId, LocalDate acknowledgedOn) {
        AgencyAppointments appointment = load(appointmentId);
        if (Statuses.APPOINTMENT_PENDING_COUNTERSIGNATURE.equals(appointment.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Appointment is queued pending a countersignature arrangement");
        }
        appointment.setAcknowledgedOn(acknowledgedOn);
        appointment.setEffectiveDate(acknowledgedOn);
        appointment.setStatus(Statuses.APPOINTMENT_ACTIVE);
        persistence.run(Update.entity(AgencyAppointments_.class).entry(appointment));

        Agencies agency = agencies.load(appointment.getAgencyId());
        if (!Statuses.AGENCY_TERMINATED.equals(agency.getStatus())) {
            agency.setStatus(Statuses.AGENCY_APPOINTED);
            persistence.run(Update.entity(Agencies_.class).entry(agency));
        }
        return load(appointmentId);
    }

    /**
     * "Appointment lapses when the agency license expires" — on the day after the
     * agency licence expires with no renewal on file, the appointment moves to
     * lapsed, new business submissions from the agency are suspended, and
     * in-force policies remain serviceable for renewal processing.
     *
     * Suspending the agency's portal users from new business while preserving
     * servicing access is the portal side of the same rule
     * (../agent-portal/manage-portal-user-access.feature.md).
     */
    public List<AgencyAppointments> lapseExpired() {
        LocalDate today = LocalDate.now(clock);
        List<AgencyAppointments> lapsed = new ArrayList<>();

        for (Agencies agency : persistence.run(Select.from(Agencies_.class))
                .listOf(Agencies.class)) {
            LocalDate expires = agency.getAgencyLicenseExpiresOn();
            if (expires == null || !today.isAfter(expires)) {
                continue;
            }
            List<AgencyAppointments> active = persistence.run(
                    Select.from(AgencyAppointments_.class)
                            .where(a -> a.agency_ID().eq(agency.getId())))
                    .listOf(AgencyAppointments.class).stream()
                    .filter(a -> !Statuses.APPOINTMENT_LAPSED.equals(a.getStatus()))
                    .toList();
            if (active.isEmpty()) {
                continue;
            }
            for (AgencyAppointments appointment : active) {
                appointment.setStatus(Statuses.APPOINTMENT_LAPSED);
                appointment.setLapsedOn(today);
                persistence.run(Update.entity(AgencyAppointments_.class).entry(appointment));
                lapsed.add(load(appointment.getId()));
            }
            suspendNewBusiness(agency.getId());
        }
        return lapsed;
    }

    /**
     * New business is suspended for every portal user at the agency; servicing
     * access for in-force policies is preserved so renewals keep processing.
     */
    private void suspendNewBusiness(String agencyId) {
        for (PortalUsers user : persistence.run(Select.from(PortalUsers_.class)
                .where(u -> u.agency_ID().eq(agencyId)))
                .listOf(PortalUsers.class)) {
            user.setNewBusinessSuspended(true);
            user.setServicingRetained(true);
            persistence.run(Update.entity(PortalUsers_.class).entry(user));
        }
    }

    /** True when the state requires a resident countersignature for the line. */
    private boolean countersignatureRequired(String state, String line) {
        return Statuses.COUNTERSIGNATURE_STATE.equalsIgnoreCase(state)
                && Statuses.LINE_COMMERCIAL_GL.equalsIgnoreCase(line);
    }

    /** Active appointments the agency holds, used by the licensing gate. */
    public List<AgencyAppointments> activeFor(String agencyId, String state) {
        return persistence.run(Select.from(AgencyAppointments_.class)
                .where(a -> a.agency_ID().eq(agencyId).and(a.state().eq(state))))
                .listOf(AgencyAppointments.class).stream()
                .filter(a -> Statuses.APPOINTMENT_ACTIVE.equals(a.getStatus()))
                .toList();
    }

    public AgencyAppointments load(String appointmentId) {
        return persistence.run(Select.from(AgencyAppointments_.class)
                .where(a -> a.ID().eq(appointmentId)))
                .first(AgencyAppointments.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Appointment not found: " + appointmentId));
    }

    private AgencyAppointments find(String agencyId, String state, String line) {
        return persistence.run(Select.from(AgencyAppointments_.class)
                .where(a -> a.agency_ID().eq(agencyId)
                        .and(a.state().eq(state))
                        .and(a.lineOfBusiness().eq(line))))
                .first(AgencyAppointments.class)
                .orElse(null);
    }
}
