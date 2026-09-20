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
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.PortalUsers;
import cds.gen.insurance.PortalUsers_;
import cds.gen.insurance.Producers;
import cds.gen.insurance.Producers_;

/**
 * Portal users administered by each agency within the roles Acme defines.
 * Access rights follow the user's licensing and the agency's appointment.
 *
 * Spec: specs/distribution/agent-portal/manage-portal-user-access.feature.md
 */
@Component
public class PortalAccessManager {

    private final PersistenceService persistence;
    private final AgencyOnboardingManager agencies;
    private final ProducerLicensingManager licensing;
    private final EmailPort email;
    private final Clock clock;

    public PortalAccessManager(PersistenceService persistence, AgencyOnboardingManager agencies,
            ProducerLicensingManager licensing, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.agencies = agencies;
        this.licensing = licensing;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Agency principal invites a licensed producer" — the invitation is emailed
     * with a link valid for 7 days, and the producer role is granted only after
     * license verification succeeds
     * (../producer-licensing/verify-producer-license.feature.md).
     */
    public PortalUsers inviteProducer(String agencyId, String npn, String fullName,
            String emailAddress) {
        Agencies agency = agencies.load(agencyId);
        LocalDate today = LocalDate.now(clock);

        Producers producer = findProducerByNpn(npn);
        if (producer == null) {
            producer = licensing.register(npn, fullName, emailAddress, agencyId,
                    agency.getDomicileState(), Statuses.ROLE_PRODUCER);
        }

        // The role is withheld until verification succeeds, so the invitation
        // carries no producer role yet.
        boolean verified = licensing.licensesOf(producer.getId()).stream()
                .anyMatch(l -> Statuses.LICENSE_ACTIVE.equals(l.getStatus()));

        PortalUsers user = PortalUsers.create();
        user.setId(UUID.randomUUID().toString());
        user.setAgencyId(agencyId);
        user.setProducerId(producer.getId());
        user.setEmail(emailAddress);
        user.setRole(verified ? Statuses.ROLE_PRODUCER : null);
        user.setLicenseVerified(verified);
        user.setStatus(verified ? Statuses.PORTAL_ACTIVE : Statuses.PORTAL_INVITED);
        user.setInvitedOn(today);
        user.setInvitationExpiresOn(today.plusDays(Statuses.INVITATION_VALID_DAYS));
        user.setServicingRetained(true);
        persistence.run(Insert.into(PortalUsers_.class).entry(user));

        email.send(new EmailMessage(emailAddress, "Your Acme agent portal invitation",
                "You have been invited to the Acme agent portal by " + agency.getLegalName()
                        + ". The invitation link is valid until "
                        + user.getInvitationExpiresOn() + "."));

        return load(user.getId());
    }

    /**
     * Grants the producer role once verification succeeds; until then an invited
     * user holds no role.
     */
    public PortalUsers grantProducerRoleOnVerification(String portalUserId) {
        PortalUsers user = load(portalUserId);
        boolean verified = user.getProducerId() != null
                && licensing.licensesOf(user.getProducerId()).stream()
                        .anyMatch(l -> Statuses.LICENSE_ACTIVE.equals(l.getStatus()));
        if (!verified) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "License verification has not succeeded for this user");
        }
        user.setLicenseVerified(true);
        user.setRole(Statuses.ROLE_PRODUCER);
        user.setStatus(Statuses.PORTAL_ACTIVE);
        persistence.run(Update.entity(PortalUsers_.class).entry(user));
        return load(portalUserId);
    }

    /**
     * "Departed producer's policies are reassigned before deactivation" — the
     * principal must name a receiving producer for the in-force policies, and
     * deactivation completes once the reassignment is recorded.
     */
    public PortalUsers deactivate(String portalUserId, String receivingProducerId) {
        PortalUsers user = load(portalUserId);
        List<Policies> inForce = inForcePoliciesOf(user.getProducerId());

        if (!inForce.isEmpty() && receivingProducerId == null) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Name a receiving producer for the " + inForce.size()
                            + " in-force policies before deactivating this account.");
        }
        if (receivingProducerId != null) {
            licensing.load(receivingProducerId);
            for (Policies policy : inForce) {
                policy.setProducerOfRecordId(receivingProducerId);
                persistence.run(Update.entity(Policies_.class).entry(policy));
            }
        }

        user.setStatus(Statuses.PORTAL_DEACTIVATED);
        persistence.run(Update.entity(PortalUsers_.class).entry(user));

        if (user.getProducerId() != null) {
            Producers producer = licensing.load(user.getProducerId());
            producer.setStatus(Statuses.PRODUCER_DEPARTED);
            producer.setDepartedOn(LocalDate.now(clock));
            persistence.run(Update.entity(Producers_.class).entry(producer));
        }
        return load(portalUserId);
    }

    /**
     * "Last administrator cannot be removed" — removing the administrator role
     * from an agency's only administrator is rejected, and the message states an
     * agency must retain at least one administrator.
     */
    public PortalUsers removeAdministratorRole(String portalUserId) {
        PortalUsers user = load(portalUserId);
        if (!Statuses.ROLE_ADMINISTRATOR.equals(user.getRole())) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "This user does not hold the administrator role");
        }
        if (administratorsOf(user.getAgencyId()).size() <= 1) {
            throw new ServiceException(ErrorStatuses.CONFLICT, Statuses.LAST_ADMIN_MESSAGE);
        }
        user.setRole(Statuses.ROLE_PRODUCER);
        persistence.run(Update.entity(PortalUsers_.class).entry(user));
        return load(portalUserId);
    }

    /**
     * "Dormant account is disabled after 90 days" — an account whose last
     * sign-in was 90 days ago is disabled, and reactivation requires the agency
     * principal to re-approve the user.
     */
    public List<PortalUsers> disableDormantAccounts() {
        LocalDate today = LocalDate.now(clock);
        List<PortalUsers> disabled = new ArrayList<>();

        for (PortalUsers user : persistence.run(Select.from(PortalUsers_.class))
                .listOf(PortalUsers.class)) {
            if (user.getLastSignInOn() == null
                    || Statuses.PORTAL_DISABLED.equals(user.getStatus())
                    || Statuses.PORTAL_DEACTIVATED.equals(user.getStatus())) {
                continue;
            }
            long dormantDays = ChronoUnit.DAYS.between(user.getLastSignInOn(), today);
            if (dormantDays < Statuses.DORMANT_ACCOUNT_DAYS) {
                continue;
            }
            user.setStatus(Statuses.PORTAL_DISABLED);
            user.setDisabledOn(today);
            user.setReactivationRequiresApproval(true);
            persistence.run(Update.entity(PortalUsers_.class).entry(user));
            disabled.add(load(user.getId()));
        }
        return disabled;
    }

    public List<PortalUsers> usersOf(String agencyId) {
        return persistence.run(Select.from(PortalUsers_.class)
                .where(u -> u.agency_ID().eq(agencyId)))
                .listOf(PortalUsers.class);
    }

    private List<PortalUsers> administratorsOf(String agencyId) {
        return usersOf(agencyId).stream()
                .filter(u -> Statuses.ROLE_ADMINISTRATOR.equals(u.getRole()))
                .filter(u -> !Statuses.PORTAL_DEACTIVATED.equals(u.getStatus()))
                .toList();
    }

    private List<Policies> inForcePoliciesOf(String producerId) {
        if (producerId == null) {
            return List.of();
        }
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.producerOfRecord_ID().eq(producerId)))
                .listOf(Policies.class).stream()
                .filter(p -> Statuses.POLICY_IN_FORCE.equals(p.getStatus()))
                .toList();
    }

    private Producers findProducerByNpn(String npn) {
        return persistence.run(Select.from(Producers_.class).where(p -> p.npn().eq(npn)))
                .first(Producers.class)
                .orElse(null);
    }

    public PortalUsers load(String portalUserId) {
        return persistence.run(Select.from(PortalUsers_.class)
                .where(u -> u.ID().eq(portalUserId)))
                .first(PortalUsers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Portal user not found: " + portalUserId));
    }
}
