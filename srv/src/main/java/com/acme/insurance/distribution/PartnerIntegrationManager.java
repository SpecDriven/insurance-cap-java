package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
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

import cds.gen.insurance.PartnerApiVersions;
import cds.gen.insurance.PartnerApiVersions_;
import cds.gen.insurance.PartnerCredentials;
import cds.gen.insurance.PartnerCredentials_;
import cds.gen.insurance.Partners;
import cds.gen.insurance.Partners_;
import cds.gen.insurance.ThrottleEvents;
import cds.gen.insurance.ThrottleEvents_;

/**
 * Onboarding a distribution partner: sandbox certification, scoped production
 * credentials, key rotation, rate limiting, and API versioning.
 *
 * Spec: specs/distribution/embedded/integrate-partner-api.feature.md
 */
@Component
public class PartnerIntegrationManager {

    private final PersistenceService persistence;
    private final Clock clock;

    public PartnerIntegrationManager(PersistenceService persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
    }

    /**
     * "Partner is certified in the sandbox before production access" — reviewing
     * the certification results issues production credentials scoped to renters
     * and personal auto, and the partner's first 30 days are monitored at a
     * reduced rate limit.
     */
    public Partners certify(String partnerId, String scopedLines, String reviewedBy) {
        Partners partner = load(partnerId);
        LocalDate today = LocalDate.now(clock);

        if (partner.getSandboxCertifiedOn() == null) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "The partner has not completed the sandbox integration test suite");
        }

        partner.setStatus(Statuses.PARTNER_CERTIFIED);
        partner.setProductionGrantedOn(today);
        // Production access is scoped to the certified lines only.
        partner.setScopedLines(scopedLines);
        partner.setMonitoringEndsOn(today.plusDays(Statuses.PARTNER_MONITORING_DAYS));
        persistence.run(Update.entity(Partners_.class).entry(partner));

        issueCredential(partnerId, today, null);
        return load(partnerId);
    }

    /**
     * "Credential rotation happens without downtime" — a replacement key is
     * issued and both keys are accepted for a 14-day overlap window; the old key
     * stops working when the window closes.
     */
    public List<PartnerCredentials> rotateCredential(String partnerId) {
        load(partnerId);
        LocalDate today = LocalDate.now(clock);
        List<PartnerCredentials> keys = new ArrayList<>();

        for (PartnerCredentials existing : activeCredentials(partnerId)) {
            existing.setStatus(Statuses.CREDENTIAL_RETIRING);
            // Accepted until the overlap window closes, then rejected.
            existing.setRetiresOn(today.plusDays(Statuses.CREDENTIAL_OVERLAP_DAYS));
            persistence.run(Update.entity(PartnerCredentials_.class).entry(existing));
            keys.add(existing);
        }

        keys.add(issueCredential(partnerId, today, null));
        return keys;
    }

    /** True while the key is accepted — active, or retiring inside the overlap. */
    public boolean acceptsKey(String partnerId, String apiKey) {
        LocalDate today = LocalDate.now(clock);
        return credentialsOf(partnerId).stream()
                .filter(c -> apiKey.equals(c.getApiKey()))
                .anyMatch(c -> Statuses.CREDENTIAL_ACTIVE.equals(c.getStatus())
                        || (Statuses.CREDENTIAL_RETIRING.equals(c.getStatus())
                                && c.getRetiresOn() != null
                                && !today.isAfter(c.getRetiresOn())));
    }

    /**
     * "Rate limiting protects the quoting service" — a partner exceeding its
     * provisioned rate gets a throttled response with a retry-after value, and
     * sustained throttling for 15 minutes opens an integration incident.
     */
    public Partners recordRequestRate(String partnerId, Integer requestsPerMinute,
            Integer sustainedMinutes) {
        Partners partner = load(partnerId);
        int limit = effectiveRateLimit(partner);

        if (requestsPerMinute <= limit) {
            return partner;
        }

        ThrottleEvents event = ThrottleEvents.create();
        event.setId(UUID.randomUUID().toString());
        event.setPartnerId(partnerId);
        event.setOccurredAt(clock.instant());
        event.setRequestsPerMinute(requestsPerMinute);
        // Excess requests are told when to come back.
        event.setRetryAfterSeconds(60);
        boolean sustained = sustainedMinutes != null
                && sustainedMinutes >= Statuses.SUSTAINED_THROTTLE_MINUTES;
        event.setIncidentOpened(sustained);
        if (sustained) {
            event.setSustainedSince(clock.instant()
                    .minusSeconds(60L * sustainedMinutes));
        }
        persistence.run(Insert.into(ThrottleEvents_.class).entry(event));

        return load(partnerId);
    }

    /** The limit in force: the reduced monitoring limit during the first 30 days. */
    public int effectiveRateLimit(Partners partner) {
        LocalDate today = LocalDate.now(clock);
        boolean monitoring = partner.getMonitoringEndsOn() != null
                && !today.isAfter(partner.getMonitoringEndsOn());
        if (monitoring && partner.getMonitoredRateLimitPerMinute() != null) {
            return partner.getMonitoredRateLimitPerMinute();
        }
        return partner.getRateLimitPerMinute() == null ? 0 : partner.getRateLimitPerMinute();
    }

    public List<ThrottleEvents> throttleEventsFor(String partnerId) {
        return persistence.run(Select.from(ThrottleEvents_.class)
                .where(t -> t.partner_ID().eq(partnerId)))
                .listOf(ThrottleEvents.class);
    }

    /**
     * "Schema change is versioned rather than breaking" — a new required field
     * is published as a new version while the prior version continues to serve,
     * and the prior version is retired no sooner than 12 months after the new
     * one is available.
     */
    public Partners publishApiVersion(Integer newVersion, LocalDate publishedOn) {
        List<PartnerApiVersions> versions = persistence.run(
                Select.from(PartnerApiVersions_.class)).listOf(PartnerApiVersions.class);

        boolean exists = versions.stream()
                .anyMatch(v -> newVersion.equals(v.getVersion()));
        if (!exists) {
            PartnerApiVersions published = PartnerApiVersions.create();
            published.setVersion(newVersion);
            published.setStatus(Statuses.API_VERSION_ACTIVE);
            published.setPublishedOn(publishedOn);
            persistence.run(Insert.into(PartnerApiVersions_.class).entry(published));
        }

        for (PartnerApiVersions prior : versions) {
            if (prior.getVersion() >= newVersion) {
                continue;
            }
            // The prior version keeps serving; only its earliest retirement moves.
            prior.setStatus(Statuses.API_VERSION_ACTIVE);
            prior.setEarliestRetirementOn(
                    publishedOn.plusMonths(Statuses.API_VERSION_RETIREMENT_MONTHS));
            persistence.run(Update.entity(PartnerApiVersions_.class).entry(prior));
        }

        // Partners stay on the version they integrated against until they move.
        return persistence.run(Select.from(Partners_.class)).first(Partners.class).orElse(null);
    }

    public List<PartnerApiVersions> apiVersions() {
        return persistence.run(Select.from(PartnerApiVersions_.class))
                .listOf(PartnerApiVersions.class);
    }

    public List<PartnerCredentials> credentialsOf(String partnerId) {
        return persistence.run(Select.from(PartnerCredentials_.class)
                .where(c -> c.partner_ID().eq(partnerId)))
                .listOf(PartnerCredentials.class);
    }

    private List<PartnerCredentials> activeCredentials(String partnerId) {
        return credentialsOf(partnerId).stream()
                .filter(c -> Statuses.CREDENTIAL_ACTIVE.equals(c.getStatus()))
                .toList();
    }

    private PartnerCredentials issueCredential(String partnerId, LocalDate issuedOn,
            LocalDate retiresOn) {
        PartnerCredentials credential = PartnerCredentials.create();
        credential.setId(UUID.randomUUID().toString());
        credential.setPartnerId(partnerId);
        credential.setApiKey("pk-" + UUID.randomUUID().toString().substring(0, 12));
        credential.setStatus(Statuses.CREDENTIAL_ACTIVE);
        credential.setIssuedOn(issuedOn);
        credential.setRetiresOn(retiresOn);
        persistence.run(Insert.into(PartnerCredentials_.class).entry(credential));
        return credential;
    }

    public Partners load(String partnerId) {
        return persistence.run(Select.from(Partners_.class).where(p -> p.ID().eq(partnerId)))
                .first(Partners.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Partner not found: " + partnerId));
    }

    public Partners byCode(String partnerCode) {
        return persistence.run(Select.from(Partners_.class)
                .where(p -> p.partnerCode().eq(partnerCode)))
                .first(Partners.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Partner not found: " + partnerCode));
    }
}
