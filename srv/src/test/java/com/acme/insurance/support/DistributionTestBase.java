package com.acme.insurance.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;

import com.acme.insurance.distribution.AgencyOnboardingManager;
import com.acme.insurance.distribution.AppointmentManager;
import com.acme.insurance.distribution.BookOfBusinessManager;
import com.acme.insurance.distribution.CommissionManager;
import com.acme.insurance.distribution.EmbeddedOfferManager;
import com.acme.insurance.distribution.LicenseRenewalManager;
import com.acme.insurance.distribution.PartnerIntegrationManager;
import com.acme.insurance.distribution.PortalAccessManager;
import com.acme.insurance.distribution.PortalQuotingManager;
import com.acme.insurance.distribution.PortalReferralManager;
import com.acme.insurance.distribution.ProducerLicensingManager;
import com.acme.insurance.distribution.ProductionBlockManager;
import com.acme.insurance.distribution.TerminationManager;
import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Update;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.Agencies_;
import cds.gen.insurance.Partners;
import cds.gen.insurance.Partners_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.PortalUsers;
import cds.gen.insurance.PortalUsers_;
import cds.gen.insurance.ProducerLicenses;
import cds.gen.insurance.Producers;

/**
 * Fixtures for the distribution scenario tests: agencies, appointments,
 * producers and their licences, portal users, and embedded partners.
 *
 * Specs: specs/distribution/**.feature.md
 */
public abstract class DistributionTestBase extends ScenarioTestBase {

    @Autowired
    protected AgencyOnboardingManager onboarding;
    @Autowired
    protected AppointmentManager appointments;
    @Autowired
    protected CommissionManager commissions;
    @Autowired
    protected TerminationManager terminations;
    @Autowired
    protected ProducerLicensingManager licensing;
    @Autowired
    protected LicenseRenewalManager licenseRenewals;
    @Autowired
    protected ProductionBlockManager blocks;
    @Autowired
    protected PortalAccessManager portalAccess;
    @Autowired
    protected PortalQuotingManager portalQuoting;
    @Autowired
    protected PortalReferralManager portalReferrals;
    @Autowired
    protected BookOfBusinessManager book;
    @Autowired
    protected PartnerIntegrationManager partners;
    @Autowired
    protected EmbeddedOfferManager offers;

    /** A distinct tax ID per fixture, so the duplicate check only fires on purpose. */
    protected String uniqueTaxId() {
        return "34-" + (1000000 + (int) (Math.random() * 8999999));
    }

    /** An Ohio agency in pending-appointment status with compliant E&O. */
    protected Agencies newAgency() {
        return newAgency("OH", null);
    }

    protected Agencies newAgency(String domicileState, Consumer<Agencies> customize) {
        Agencies agency = db(() -> onboarding.submitPacket(
                "Buckeye Insurance Partners LLC", uniqueTaxId(), domicileState, true,
                new BigDecimal("1000000.00"), new BigDecimal("3000000.00"), true,
                "Dana Whitfield", "dana.whitfield@buckeye.example"));
        if (customize != null) {
            db(() -> {
                Agencies loaded = onboarding.load(agency.getId());
                customize.accept(loaded);
                persistence.run(Update.entity(Agencies_.class).entry(loaded));
                return loaded;
            });
        }
        return db(() -> onboarding.load(agency.getId()));
    }

    /** An agency appointed and acknowledged in the state for the given lines. */
    protected Agencies newAppointedAgency(String state, String... lines) {
        Agencies agency = newAgency(state, null);
        db(() -> {
            var created = appointments.appoint(agency.getId(), state, List.of(lines));
            created.forEach(a -> appointments.acknowledge(a.getId(), BASE_DATE.minusMonths(6)));
            return created;
        });
        return db(() -> onboarding.load(agency.getId()));
    }

    /** A producer at the agency, licensed and active in the given state. */
    protected Producers newLicensedProducer(String agencyId, String npn, String state) {
        Producers producer = db(() -> licensing.register(npn, "Roberto Alvarez",
                "roberto.alvarez@buckeye.example", agencyId, state, Statuses.ROLE_PRODUCER));
        db(() -> licensing.verify(producer.getId(), state, "Roberto Alvarez",
                Statuses.LICENSE_CLASS_PC, BASE_DATE.plusYears(1)));
        return db(() -> licensing.load(producer.getId()));
    }

    /** A producer with no licence on file anywhere. */
    protected Producers newUnlicensedProducer(String agencyId, String npn) {
        return db(() -> licensing.register(npn, "Casey Nolan",
                "casey.nolan@buckeye.example", agencyId, "OH", Statuses.ROLE_SERVICE));
    }

    protected ProducerLicenses licenseOf(String producerId, String state) {
        return db(() -> licensing.find(producerId, state));
    }

    protected PortalUsers insertPortalUser(String agencyId, String producerId, String role,
            String status, LocalDate lastSignInOn) {
        PortalUsers user = PortalUsers.create();
        user.setId(UUID.randomUUID().toString());
        user.setAgencyId(agencyId);
        user.setProducerId(producerId);
        user.setEmail("portal.user+" + UUID.randomUUID().toString().substring(0, 6)
                + "@buckeye.example");
        user.setRole(role);
        user.setStatus(status);
        user.setLastSignInOn(lastSignInOn);
        user.setLicenseVerified(true);
        user.setServicingRetained(true);
        dbRun(() -> persistence.run(Insert.into(PortalUsers_.class).entry(user)));
        return db(() -> portalAccess.load(user.getId()));
    }

    /** A partner certified in the sandbox, writing under a licensed agency. */
    protected Partners insertPartner(String partnerCode, String agencyOfRecordId,
            Consumer<Partners> customize) {
        Partners partner = Partners.create();
        partner.setId(UUID.randomUUID().toString());
        partner.setPartnerCode(partnerCode);
        partner.setName(partnerCode + " Distribution");
        partner.setStatus(Statuses.PARTNER_SANDBOX);
        partner.setLicensedEntity(false);
        partner.setAgencyOfRecordId(agencyOfRecordId);
        partner.setSandboxCertifiedOn(BASE_DATE.minusDays(7));
        partner.setRateLimitPerMinute(120);
        partner.setMonitoredRateLimitPerMinute(30);
        partner.setSchemaVersion(2);
        if (customize != null) {
            customize.accept(partner);
        }
        Partners created = partner;
        dbRun(() -> persistence.run(Insert.into(Partners_.class).entry(created)));
        return db(() -> partners.load(created.getId()));
    }

    /** An in-force auto policy produced by the agency, for book and roll tests. */
    protected Policies insertAgencyPolicy(String agencyId, String producerId,
            BigDecimal premium, LocalDate effectiveDate, Consumer<Policies> customize) {
        return insertPaidInFullAutoPolicy(premium, effectiveDate, policy -> {
            policy.setProducingAgencyId(agencyId);
            policy.setProducerOfRecordId(producerId);
            if (customize != null) {
                customize.accept(policy);
            }
        });
    }

    /** Removes policies a scenario added, so unfiltered table assertions elsewhere hold. */
    protected void deletePolicies(String... policyIds) {
        dbRun(() -> {
            for (String policyId : policyIds) {
                persistence.run(com.sap.cds.ql.Delete
                        .from(cds.gen.insurance.RenewalOffers_.class)
                        .where(o -> o.policy_ID().eq(policyId)));
                persistence.run(com.sap.cds.ql.Delete.from(Policies_.class)
                        .where(p -> p.ID().eq(policyId)));
            }
        });
    }

    protected List<String> emailSubjects() {
        return emails.sent().stream().map(m -> m.subject()).toList();
    }

    protected List<String> emailBodies() {
        return emails.sent().stream().map(m -> m.body()).toList();
    }
}
