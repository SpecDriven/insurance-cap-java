package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.PartnerApiVersions;
import cds.gen.insurance.PartnerCredentials;
import cds.gen.insurance.Partners;
import cds.gen.insurance.ThrottleEvents;

/**
 * Spec: specs/distribution/embedded/integrate-partner-api.feature.md
 * "Partner must hold or borrow a licensed entity" and "Suspended partner stops
 * receiving offers" are [proposed] and intentionally absent.
 */
@DisplayName("Integrate a Distribution Partner API — specs/distribution/embedded/integrate-partner-api.feature.md")
class IntegratePartnerApiTest extends DistributionTestBase {

    @Test
    @DisplayName("Partner is certified in the sandbox before production access @v1")
    void partnerIsCertifiedInTheSandboxBeforeProductionAccess() {
        // A partner that has completed the integration test suite in the sandbox
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner("LEASEHUB1", agency.getId(), null);

        assertThat(partner.getStatus()).isEqualTo(Statuses.PARTNER_SANDBOX);

        // The integration manager reviews the certification results
        Partners certified = db(() -> partners.certify(partner.getId(),
                "renters, personal auto", "Integration Manager"));

        // Production credentials are issued scoped to renters and personal auto
        assertThat(certified.getStatus()).isEqualTo(Statuses.PARTNER_CERTIFIED);
        assertThat(certified.getScopedLines()).isEqualTo("renters, personal auto");
        assertThat(certified.getProductionGrantedOn()).isEqualTo(BASE_DATE);

        List<PartnerCredentials> credentials =
                db(() -> partners.credentialsOf(partner.getId()));
        assertThat(credentials).hasSize(1);
        assertThat(credentials.get(0).getStatus()).isEqualTo(Statuses.CREDENTIAL_ACTIVE);

        // The partner's first 30 days are monitored at a reduced rate limit
        assertThat(certified.getMonitoringEndsOn()).isEqualTo(BASE_DATE.plusDays(30));
        assertThat(db(() -> partners.effectiveRateLimit(certified))).isEqualTo(30);

        // After the monitoring window the full provisioned limit applies
        clock.setBusinessDate(BASE_DATE.plusDays(31));
        try {
            assertThat(db(() -> partners.effectiveRateLimit(
                    db(() -> partners.load(partner.getId()))))).isEqualTo(120);
        } finally {
            clock.setBusinessDate(BASE_DATE);
        }
    }

    @Test
    @DisplayName("Partner is certified in the sandbox before production access @v1 — uncertified is refused")
    void uncertifiedPartnerIsRefusedProductionAccess() {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner("LEASEHUB2", agency.getId(),
                p -> p.setSandboxCertifiedOn(null));

        // Production access requires the sandbox suite to have been completed
        assertThatThrownBy(() -> db(() -> partners.certify(partner.getId(),
                "renters", "Integration Manager")))
                .hasMessageContaining("has not completed the sandbox integration test suite");
    }

    @Test
    @DisplayName("Credential rotation happens without downtime @v1")
    void credentialRotationHappensWithoutDowntime() {
        // A partner with an active API key nearing its 12-month age
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner("LEASEHUB3", agency.getId(), null);
        db(() -> partners.certify(partner.getId(), "renters", "Integration Manager"));
        String oldKey = db(() -> partners.credentialsOf(partner.getId())).get(0).getApiKey();

        // A replacement key is issued
        List<PartnerCredentials> keys = db(() -> partners.rotateCredential(partner.getId()));
        assertThat(keys).hasSize(2);

        String newKey = keys.stream()
                .filter(c -> Statuses.CREDENTIAL_ACTIVE.equals(c.getStatus()))
                .findFirst().orElseThrow().getApiKey();
        assertThat(newKey).isNotEqualTo(oldKey);

        // Both keys are accepted for a 14-day overlap window
        assertThat(db(() -> partners.acceptsKey(partner.getId(), oldKey))).isTrue();
        assertThat(db(() -> partners.acceptsKey(partner.getId(), newKey))).isTrue();

        clock.setBusinessDate(BASE_DATE.plusDays(14));
        assertThat(db(() -> partners.acceptsKey(partner.getId(), oldKey))).isTrue();

        // The old key stops working when the window closes
        clock.setBusinessDate(BASE_DATE.plusDays(15));
        try {
            assertThat(db(() -> partners.acceptsKey(partner.getId(), oldKey))).isFalse();
            assertThat(db(() -> partners.acceptsKey(partner.getId(), newKey))).isTrue();
        } finally {
            clock.setBusinessDate(BASE_DATE);
        }
    }

    @Test
    @DisplayName("Rate limiting protects the quoting service @v1")
    void rateLimitingProtectsTheQuotingService() {
        // A partner provisioned at 120 quote requests per minute, past its
        // monitoring window so the full limit applies
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner("LEASEHUB4", agency.getId(),
                p -> p.setMonitoringEndsOn(BASE_DATE.minusDays(1)));

        // Inside the limit nothing is throttled
        db(() -> partners.recordRequestRate(partner.getId(), 118, 0));
        assertThat(db(() -> partners.throttleEventsFor(partner.getId()))).isEmpty();

        // The partner exceeds that rate
        db(() -> partners.recordRequestRate(partner.getId(), 145, 2));

        // Excess requests receive a throttled response with a retry-after value
        List<ThrottleEvents> events = db(() -> partners.throttleEventsFor(partner.getId()));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getRetryAfterSeconds()).isEqualTo(60);
        assertThat(events.get(0).getIncidentOpened()).isFalse();

        // Sustained throttling for 15 minutes opens an integration incident
        db(() -> partners.recordRequestRate(partner.getId(), 145, 15));
        assertThat(db(() -> partners.throttleEventsFor(partner.getId())))
                .filteredOn(e -> Boolean.TRUE.equals(e.getIncidentOpened()))
                .hasSize(1);
    }

    @Test
    @DisplayName("Schema change is versioned rather than breaking @v1")
    void schemaChangeIsVersionedRatherThanBreaking() {
        // Partners integrated against version 2 of the quote endpoint
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner("LEASEHUB5", agency.getId(),
                p -> p.setSchemaVersion(2));

        // A new required field is introduced
        LocalDate publishedOn = LocalDate.of(2026, 6, 1);
        db(() -> partners.publishApiVersion(4, publishedOn));

        List<PartnerApiVersions> versions = db(() -> partners.apiVersions());
        PartnerApiVersions v4 = versionOf(versions, 4);
        PartnerApiVersions v2 = versionOf(versions, 2);

        // The change is published as a new version
        assertThat(v4.getStatus()).isEqualTo(Statuses.API_VERSION_ACTIVE);
        assertThat(v4.getPublishedOn()).isEqualTo(publishedOn);

        // Version 2 continues to serve
        assertThat(v2.getStatus()).isEqualTo(Statuses.API_VERSION_ACTIVE);
        assertThat(db(() -> partners.load(partner.getId())).getSchemaVersion()).isEqualTo(2);

        // Version 2 is retired no sooner than 12 months after the new version is
        // available
        assertThat(v2.getEarliestRetirementOn()).isEqualTo(publishedOn.plusMonths(12));
    }

    private static PartnerApiVersions versionOf(List<PartnerApiVersions> versions, int version) {
        return versions.stream()
                .filter(v -> v.getVersion() == version)
                .findFirst().orElseThrow();
    }
}
