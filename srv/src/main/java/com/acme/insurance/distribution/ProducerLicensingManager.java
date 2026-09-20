package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.LocalDate;
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
import cds.gen.insurance.Producers;
import cds.gen.insurance.Producers_;
import cds.gen.insurance.ProducerLicenses;
import cds.gen.insurance.ProducerLicenses_;

/**
 * Verification of individual producer licences against the national registry,
 * per state of the risk.
 *
 * Spec: specs/distribution/producer-licensing/verify-producer-license.feature.md
 */
@Component
public class ProducerLicensingManager {

    private final PersistenceService persistence;
    private final AgencyOnboardingManager agencies;
    private final EmailPort email;
    private final Clock clock;

    public ProducerLicensingManager(PersistenceService persistence,
            AgencyOnboardingManager agencies, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.agencies = agencies;
        this.email = email;
        this.clock = clock;
    }

    public Producers register(String npn, String fullName, String emailAddress, String agencyId,
            String residentState, String role) {
        Producers producer = Producers.create();
        producer.setId(UUID.randomUUID().toString());
        producer.setNpn(npn);
        producer.setFullName(fullName);
        producer.setEmail(emailAddress);
        producer.setAgencyId(agencyId);
        producer.setResidentState(residentState);
        producer.setRole(role);
        producer.setStatus(Statuses.PRODUCER_ACTIVE);
        persistence.run(Insert.into(Producers_.class).entry(producer));
        return load(producer.getId());
    }

    /**
     * "Producer license verified against the national registry" — querying the
     * registry for NPN 8841207 confirms the Ohio property and casualty licence
     * active, and the result is stamped with the query date.
     *
     * "Name mismatch is routed for manual reconciliation" — when the registry
     * returns a different name for the same NPN the record is flagged for manual
     * reconciliation rather than failed, and production continues for 30 days.
     */
    public ProducerLicenses verify(String producerId, String state, String registryName,
            String licenseClass, LocalDate expiresOn) {
        Producers producer = load(producerId);
        LocalDate today = LocalDate.now(clock);

        ProducerLicenses license = find(producerId, state);
        if (license == null) {
            license = ProducerLicenses.create();
            license.setId(UUID.randomUUID().toString());
            license.setProducerId(producerId);
            license.setState(state);
            license.setNonResident(!state.equalsIgnoreCase(producer.getResidentState()));
            persistence.run(Insert.into(ProducerLicenses_.class).entry(license));
        }

        license.setLicenseClass(licenseClass);
        license.setExpiresOn(expiresOn);
        license.setStatus(Statuses.LICENSE_ACTIVE);
        license.setLastVerifiedOn(today);
        license.setVerificationSource(Statuses.REGISTRY_NATIONAL);
        license.setNameOnRegistry(registryName);
        license.setReinstatementWindowMonths(Statuses.REINSTATEMENT_WINDOW_MONTHS);

        boolean mismatch = registryName != null
                && !registryName.equalsIgnoreCase(producer.getFullName());
        license.setNameMismatch(mismatch);
        // A mismatch is reconciled by hand; production continues meanwhile.
        license.setMismatchGraceDays(mismatch ? Statuses.NAME_MISMATCH_GRACE_DAYS : null);

        persistence.run(Update.entity(ProducerLicenses_.class).entry(license));
        return loadLicense(license.getId());
    }

    /**
     * "Non-resident license is required for out-of-state risks" — the licensing
     * check runs on the state of the risk, so an Ohio resident licence alone is
     * not sufficient for a risk garaged in Kentucky; an active Kentucky
     * non-resident licence is required.
     *
     * Returns the licence that authorizes the risk, or throws naming what is
     * missing.
     */
    public ProducerLicenses checkForRisk(String producerId, String riskState) {
        Producers producer = load(producerId);
        ProducerLicenses license = find(producerId, riskState);

        if (license == null || !Statuses.LICENSE_ACTIVE.equals(license.getStatus())) {
            boolean nonResident = !riskState.equalsIgnoreCase(producer.getResidentState());
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "An active " + riskState + (nonResident ? " non-resident" : "")
                            + " license is required for a risk in " + riskState
                            + "; the " + producer.getResidentState()
                            + " resident license alone is not sufficient.");
        }
        return license;
    }

    /**
     * "Suspended license halts binding the same day" — a DOI suspension appearing
     * in the nightly registry feed revokes binding authority effective the
     * suspension date, and the agency principal is notified by email.
     */
    public ProducerLicenses recordSuspension(String producerId, String state,
            LocalDate suspendedOn) {
        Producers producer = load(producerId);
        ProducerLicenses license = find(producerId, state);
        if (license == null) {
            throw new ServiceException(ErrorStatuses.NOT_FOUND,
                    "No " + state + " license on file for producer " + producer.getNpn());
        }

        license.setStatus(Statuses.LICENSE_SUSPENDED);
        license.setSuspendedOn(suspendedOn);
        persistence.run(Update.entity(ProducerLicenses_.class).entry(license));

        producer.setStatus(Statuses.PRODUCER_SUSPENDED);
        // Binding authority is revoked effective the date of the suspension.
        producer.setBindingRevokedOn(suspendedOn);
        persistence.run(Update.entity(Producers_.class).entry(producer));

        Agencies agency = producer.getAgencyId() == null
                ? null : agencies.load(producer.getAgencyId());
        if (agency != null && agency.getPrincipalEmail() != null) {
            email.send(new EmailMessage(agency.getPrincipalEmail(),
                    "Producer license suspended",
                    "The " + state + " license for " + producer.getFullName()
                            + " (NPN " + producer.getNpn() + ") was suspended by the DOI on "
                            + suspendedOn + ". Binding authority is revoked effective that date."));
        }
        return loadLicense(license.getId());
    }

    /** Active, unsuspended licences the producer holds in the state. */
    public boolean isAuthorized(String producerId, String state) {
        ProducerLicenses license = find(producerId, state);
        if (license == null) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
        boolean unexpired = license.getExpiresOn() == null
                || !today.isAfter(license.getExpiresOn());
        return Statuses.LICENSE_ACTIVE.equals(license.getStatus()) && unexpired;
    }

    public ProducerLicenses find(String producerId, String state) {
        return persistence.run(Select.from(ProducerLicenses_.class)
                .where(l -> l.producer_ID().eq(producerId).and(l.state().eq(state))))
                .first(ProducerLicenses.class)
                .orElse(null);
    }

    public List<ProducerLicenses> licensesOf(String producerId) {
        return persistence.run(Select.from(ProducerLicenses_.class)
                .where(l -> l.producer_ID().eq(producerId)))
                .listOf(ProducerLicenses.class);
    }

    public Producers load(String producerId) {
        return persistence.run(Select.from(Producers_.class).where(p -> p.ID().eq(producerId)))
                .first(Producers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Producer not found: " + producerId));
    }

    public ProducerLicenses loadLicense(String licenseId) {
        return persistence.run(Select.from(ProducerLicenses_.class)
                .where(l -> l.ID().eq(licenseId)))
                .first(ProducerLicenses.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "License not found: " + licenseId));
    }
}
