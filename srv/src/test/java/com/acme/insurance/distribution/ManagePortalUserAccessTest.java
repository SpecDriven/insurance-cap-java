package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.Policies;
import cds.gen.insurance.PortalUsers;
import cds.gen.insurance.Producers;

/**
 * Spec: specs/distribution/agent-portal/manage-portal-user-access.feature.md
 * "Appointment lapse suspends every agency user" and "Customer service role
 * excludes binding" are [proposed] and intentionally absent.
 */
@DisplayName("Manage Portal User Access — specs/distribution/agent-portal/manage-portal-user-access.feature.md")
class ManagePortalUserAccessTest extends DistributionTestBase {

    @Test
    @DisplayName("Agency principal invites a licensed producer @v1")
    void agencyPrincipalInvitesALicensedProducer() {
        // An agency principal with the administrator role
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);

        // The principal invites a producer with national producer number 9930114
        PortalUsers invited = db(() -> portalAccess.inviteProducer(agency.getId(), "9930114",
                "Jonas Berg", "jonas.berg@buckeye.example"));

        // An invitation is emailed with a link valid for 7 days
        assertThat(emailSubjects()).contains("Your Acme agent portal invitation");
        assertThat(invited.getInvitedOn()).isEqualTo(BASE_DATE);
        assertThat(invited.getInvitationExpiresOn()).isEqualTo(BASE_DATE.plusDays(7));

        // The producer role is granted only after license verification succeeds
        assertThat(invited.getStatus()).isEqualTo(Statuses.PORTAL_INVITED);
        assertThat(invited.getLicenseVerified()).isFalse();
        assertThat(invited.getRole()).isNull();

        // Verification succeeds, and only then is the role granted
        db(() -> licensing.verify(invited.getProducerId(), "OH", "Jonas Berg",
                Statuses.LICENSE_CLASS_PC, BASE_DATE.plusYears(1)));
        PortalUsers granted =
                db(() -> portalAccess.grantProducerRoleOnVerification(invited.getId()));

        assertThat(granted.getRole()).isEqualTo(Statuses.ROLE_PRODUCER);
        assertThat(granted.getStatus()).isEqualTo(Statuses.PORTAL_ACTIVE);
    }

    @Test
    @DisplayName("Departed producer's policies are reassigned before deactivation @v1")
    void departedProducersPoliciesAreReassignedBeforeDeactivation() {
        // A producer with in-force policies leaving the agency
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers leaving = newLicensedProducer(agency.getId(), "9930115", "OH");
        Producers receiving = newLicensedProducer(agency.getId(), "9930116", "OH");
        PortalUsers user = insertPortalUser(agency.getId(), leaving.getId(),
                Statuses.ROLE_PRODUCER, Statuses.PORTAL_ACTIVE, BASE_DATE.minusDays(1));

        Policies first = insertAgencyPolicy(agency.getId(), leaving.getId(),
                new BigDecimal("517.00"), BASE_DATE.minusMonths(1), null);
        Policies second = insertAgencyPolicy(agency.getId(), leaving.getId(),
                new BigDecimal("642.00"), BASE_DATE.minusMonths(2), null);

        try {
            // The principal deactivates the producer's account without naming a
            // receiving producer
            assertThatThrownBy(() -> db(() -> portalAccess.deactivate(user.getId(), null)))
                    // The principal must name a receiving producer for the
                    // in-force policies
                    .hasMessageContaining("Name a receiving producer")
                    .hasMessageContaining("2 in-force policies");

            assertThat(db(() -> portalAccess.load(user.getId())).getStatus())
                    .isEqualTo(Statuses.PORTAL_ACTIVE);

            // Deactivation completes once the reassignment is recorded
            PortalUsers deactivated =
                    db(() -> portalAccess.deactivate(user.getId(), receiving.getId()));

            assertThat(deactivated.getStatus()).isEqualTo(Statuses.PORTAL_DEACTIVATED);
            assertThat(db(() -> reloadPolicy(first.getId())).getProducerOfRecordId())
                    .isEqualTo(receiving.getId());
            assertThat(db(() -> reloadPolicy(second.getId())).getProducerOfRecordId())
                    .isEqualTo(receiving.getId());
        } finally {
            deletePolicies(first.getId(), second.getId());
        }
    }

    @Test
    @DisplayName("Dormant account is disabled after 90 days @v1")
    void dormantAccountIsDisabledAfter90Days() {
        // A portal user whose last sign-in was 1 March 2026
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        LocalDate lastSignIn = LocalDate.of(2026, 3, 1);
        PortalUsers user = insertPortalUser(agency.getId(), null, Statuses.ROLE_PRODUCER,
                Statuses.PORTAL_ACTIVE, lastSignIn);

        // A user who has signed in recently, to show the sweep is selective
        PortalUsers recent = insertPortalUser(agency.getId(), null, Statuses.ROLE_PRODUCER,
                Statuses.PORTAL_ACTIVE, LocalDate.of(2026, 5, 20));

        // 30 May 2026 passes with no sign-in — 90 days after 1 March
        LocalDate may30 = LocalDate.of(2026, 5, 30);
        clock.setBusinessDate(may30);
        List<PortalUsers> disabled = db(() -> portalAccess.disableDormantAccounts());
        clock.setBusinessDate(BASE_DATE);

        // The account is disabled
        assertThat(disabled).extracting(PortalUsers::getId).contains(user.getId());
        PortalUsers reloaded = db(() -> portalAccess.load(user.getId()));
        assertThat(reloaded.getStatus()).isEqualTo(Statuses.PORTAL_DISABLED);
        assertThat(reloaded.getDisabledOn()).isEqualTo(may30);

        // Reactivation requires the agency principal to re-approve the user
        assertThat(reloaded.getReactivationRequiresApproval()).isTrue();

        // The recently active user is untouched
        assertThat(db(() -> portalAccess.load(recent.getId())).getStatus())
                .isEqualTo(Statuses.PORTAL_ACTIVE);
    }

    @Test
    @DisplayName("Last administrator cannot be removed @v1")
    void lastAdministratorCannotBeRemoved() {
        // An agency with exactly one user holding the administrator role
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        PortalUsers onlyAdmin = insertPortalUser(agency.getId(), null,
                Statuses.ROLE_ADMINISTRATOR, Statuses.PORTAL_ACTIVE, BASE_DATE.minusDays(1));

        // That user's administrator role is removed
        assertThatThrownBy(() ->
                db(() -> portalAccess.removeAdministratorRole(onlyAdmin.getId())))
                // The change is rejected, and the message states an agency must
                // retain at least one administrator
                .hasMessageContaining(Statuses.LAST_ADMIN_MESSAGE);

        assertThat(db(() -> portalAccess.load(onlyAdmin.getId())).getRole())
                .isEqualTo(Statuses.ROLE_ADMINISTRATOR);

        // With a second administrator on file, the removal goes through
        insertPortalUser(agency.getId(), null, Statuses.ROLE_ADMINISTRATOR,
                Statuses.PORTAL_ACTIVE, BASE_DATE.minusDays(1));
        PortalUsers demoted = db(() -> portalAccess.removeAdministratorRole(onlyAdmin.getId()));
        assertThat(demoted.getRole()).isEqualTo(Statuses.ROLE_PRODUCER);
    }
}
