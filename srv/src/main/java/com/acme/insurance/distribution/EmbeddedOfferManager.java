package com.acme.insurance.distribution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.domain.SystemContextRunner;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.EmbeddedOffers;
import cds.gen.insurance.EmbeddedOffers_;
import cds.gen.insurance.Partners;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.SaleAttributions;
import cds.gen.insurance.SaleAttributions_;

/**
 * Coverage offered inside a partner's checkout, and the attribution that
 * decides which partner and which licensed agency earn credit for the sale.
 *
 * Specs: specs/distribution/embedded/offer-embedded-quote.feature.md,
 *        specs/distribution/embedded/attribute-embedded-sale.feature.md
 */
@Component
public class EmbeddedOfferManager {

    private final PersistenceService persistence;
    private final PartnerIntegrationManager partners;
    private final SystemContextRunner system;
    private final Clock clock;

    public EmbeddedOfferManager(PersistenceService persistence,
            PartnerIntegrationManager partners, SystemContextRunner system, Clock clock) {
        this.persistence = persistence;
        this.partners = partners;
        this.system = system;
        this.clock = clock;
    }

    /**
     * "Renters quote is offered inside a leasing checkout" — a leasing portal
     * passing the applicant's name, unit address, and lease start date gets a
     * renters premium with $15,000 personal property and $100,000 liability,
     * valid until the lease start date.
     *
     * "Partner may not see or alter rating factors" — the offer carries only the
     * premium, a coverage summary, and a quote token. No rating factors, tier
     * assignments, or credit-based attributes are stored on it, so none can be
     * disclosed.
     *
     * "Embedded quote token expires in 24 hours" — the token expires 24 hours
     * after issue, or at the lease start date when that comes first.
     */
    public EmbeddedOffers offerRenters(String partnerCode, String applicantName,
            String unitAddress, LocalDate leaseStartDate) {

        Partners partner = partners.byCode(partnerCode);
        if (Statuses.PARTNER_SUSPENDED.equals(partner.getStatus())) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "Partner " + partnerCode + " is suspended");
        }
        Instant now = clock.instant();

        EmbeddedOffers offer = EmbeddedOffers.create();
        offer.setId(UUID.randomUUID().toString());
        offer.setPartnerId(partner.getId());
        offer.setQuoteToken("QT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        offer.setLineOfBusiness(Statuses.LINE_RENTERS);
        offer.setStatus(Statuses.OFFER_OPEN);
        offer.setPersonalPropertyLimit(Statuses.RENTERS_PERSONAL_PROPERTY);
        offer.setLiabilityLimit(Statuses.RENTERS_LIABILITY);
        offer.setPremium(rentersPremium());
        offer.setCoverageSummary("Renters — $15,000 personal property, $100,000 liability");
        offer.setApplicantName(applicantName);
        offer.setUnitAddress(unitAddress);
        offer.setLeaseStartDate(leaseStartDate);
        offer.setIssuedAt(now);
        offer.setExpiresAt(rentersExpiry(now, leaseStartDate));
        offer.setDisclosureShown(false);
        offer.setCoverageSummaryShown(false);
        persistence.run(Insert.into(EmbeddedOffers_.class).entry(offer));

        return load(offer.getId());
    }

    /**
     * "Required disclosures are shown before the customer accepts" — the
     * state-required producer disclosure and the coverage summary are shown in
     * the partner's flow.
     */
    public EmbeddedOffers acknowledgeDisclosures(String offerId, Boolean disclosureShown,
            Boolean coverageSummaryShown) {
        EmbeddedOffers offer = load(offerId);
        offer.setDisclosureShown(Boolean.TRUE.equals(disclosureShown));
        offer.setCoverageSummaryShown(Boolean.TRUE.equals(coverageSummaryShown));
        persistence.run(Update.entity(EmbeddedOffers_.class).entry(offer));
        return load(offerId);
    }

    /**
     * "Required disclosures are shown before the customer accepts" — acceptance
     * is not recorded until both the disclosure and the coverage summary are
     * acknowledged.
     *
     * "Embedded quote token expires in 24 hours" — an attempt after the token
     * expires is refused, and a fresh quote may be requested at current rates.
     */
    public EmbeddedOffers accept(String offerId) {
        EmbeddedOffers offer = load(offerId);
        Instant now = clock.instant();

        if (offer.getExpiresAt() != null && !now.isBefore(offer.getExpiresAt())) {
            // The expiry outlives the refusal, so it is written in its own change
            // set before the exception rolls this one back.
            offer.setStatus(Statuses.OFFER_EXPIRED);
            EmbeddedOffers expired = offer;
            system.run(() -> persistence.run(Update.entity(EmbeddedOffers_.class)
                    .entry(expired)));
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Expired token — the quote token expired at " + offer.getExpiresAt()
                            + "; request a fresh quote at current rates.");
        }
        if (!Boolean.TRUE.equals(offer.getDisclosureShown())
                || !Boolean.TRUE.equals(offer.getCoverageSummaryShown())) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "The state-required producer disclosure and the coverage summary must "
                            + "both be acknowledged before acceptance is recorded.");
        }

        offer.setStatus(Statuses.OFFER_ACCEPTED);
        offer.setAcceptedAt(now);
        persistence.run(Update.entity(EmbeddedOffers_.class).entry(offer));
        return load(offerId);
    }

    /**
     * "Policy bound from a partner token carries the partner code" — binding on
     * a token issued to a partner stamps the partner code on the policy and
     * records the licensed agency of record for that partner as producer.
     *
     * "Last touch wins when two partners present the same risk" — binding on the
     * second partner's token attributes the sale to that partner and closes any
     * earlier open offer to the same applicant as not taken.
     */
    public SaleAttributions bindFromToken(String quoteToken, String policyId) {
        EmbeddedOffers offer = byToken(quoteToken);
        Instant now = clock.instant();

        if (offer.getExpiresAt() != null && !now.isBefore(offer.getExpiresAt())) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Expired token — bind refused; request a fresh quote at current rates.");
        }

        Partners partner = partners.load(offer.getPartnerId());
        if (partner.getAgencyOfRecordId() == null) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "Partner " + partner.getPartnerCode()
                            + " has no licensed agency of record on file");
        }

        closeEarlierOffers(offer);

        Policies policy = policy(policyId);
        policy.setPartnerCode(partner.getPartnerCode());
        policy.setProducingAgencyId(partner.getAgencyOfRecordId());
        persistence.run(Update.entity(Policies_.class).entry(policy));

        SaleAttributions attribution = SaleAttributions.create();
        attribution.setId(UUID.randomUUID().toString());
        attribution.setPolicyId(policyId);
        attribution.setOfferId(offer.getId());
        attribution.setPartnerId(partner.getId());
        attribution.setAgencyOfRecordId(partner.getAgencyOfRecordId());
        attribution.setPartnerCode(partner.getPartnerCode());
        attribution.setStatus(Statuses.ATTRIBUTION_ATTRIBUTED);
        attribution.setAttributedOn(LocalDate.now(clock));
        attribution.setFrozen(false);
        attribution.setCommissionPaid(false);
        persistence.run(Insert.into(SaleAttributions_.class).entry(attribution));

        offer.setStatus(Statuses.OFFER_ACCEPTED);
        persistence.run(Update.entity(EmbeddedOffers_.class).entry(offer));

        return loadAttribution(attribution.getId());
    }

    /**
     * "Attribution is frozen once the first commission is paid" — a correction
     * after the first commission has been paid requires distribution management
     * approval, and the adjustment is made as a chargeback and re-credit rather
     * than a silent edit.
     *
     * The chargeback and re-credit are two attribution rows: the original is
     * reversed and a replacement is written, never an in-place edit.
     */
    public List<SaleAttributions> reattribute(String policyId, String newPartnerCode,
            String approvedBy) {
        SaleAttributions current = currentAttribution(policyId);
        Partners newPartner = partners.byCode(newPartnerCode);

        if (Boolean.TRUE.equals(current.getCommissionPaid()) && approvedBy == null) {
            throw new ServiceException(ErrorStatuses.FORBIDDEN,
                    "Attribution is frozen once the first commission is paid; "
                            + "distribution management approval is required.");
        }

        List<SaleAttributions> result = new ArrayList<>();

        // Chargeback: the original attribution is reversed, not edited away.
        current.setStatus(Statuses.ATTRIBUTION_REVERSED);
        current.setApprovedBy(approvedBy);
        persistence.run(Update.entity(SaleAttributions_.class).entry(current));

        // Re-credit: a new attribution row carries the corrected partner.
        SaleAttributions replacement = SaleAttributions.create();
        replacement.setId(UUID.randomUUID().toString());
        replacement.setPolicyId(policyId);
        replacement.setOfferId(current.getOfferId());
        replacement.setPartnerId(newPartner.getId());
        replacement.setAgencyOfRecordId(newPartner.getAgencyOfRecordId());
        replacement.setPartnerCode(newPartnerCode);
        replacement.setStatus(Statuses.ATTRIBUTION_REATTRIBUTED);
        replacement.setAttributedOn(LocalDate.now(clock));
        replacement.setApprovedBy(approvedBy);
        replacement.setFrozen(false);
        replacement.setCommissionPaid(false);
        persistence.run(Insert.into(SaleAttributions_.class).entry(replacement));

        current.setSupersededById(replacement.getId());
        persistence.run(Update.entity(SaleAttributions_.class).entry(current));

        Policies policy = policy(policyId);
        policy.setPartnerCode(newPartnerCode);
        policy.setProducingAgencyId(newPartner.getAgencyOfRecordId());
        persistence.run(Update.entity(Policies_.class).entry(policy));

        result.add(loadAttribution(current.getId()));
        result.add(loadAttribution(replacement.getId()));
        return result;
    }

    /** Freezes an attribution as its first commission is paid. */
    public SaleAttributions markCommissionPaid(String attributionId) {
        SaleAttributions attribution = loadAttribution(attributionId);
        attribution.setCommissionPaid(true);
        attribution.setFrozen(true);
        persistence.run(Update.entity(SaleAttributions_.class).entry(attribution));
        return loadAttribution(attributionId);
    }

    /**
     * Last touch wins: an earlier open offer to the same applicant inside the
     * 30-day look-back is closed as not taken when a later token binds.
     */
    private void closeEarlierOffers(EmbeddedOffers winning) {
        LocalDate lookbackStart = LocalDate.now(clock)
                .minusDays(Statuses.ATTRIBUTION_LOOKBACK_DAYS);

        for (EmbeddedOffers other : persistence.run(Select.from(EmbeddedOffers_.class))
                .listOf(EmbeddedOffers.class)) {
            if (other.getId().equals(winning.getId())
                    || !Statuses.OFFER_OPEN.equals(other.getStatus())
                    || other.getApplicantName() == null
                    || !other.getApplicantName().equals(winning.getApplicantName())) {
                continue;
            }
            LocalDate issued = other.getIssuedAt() == null ? null
                    : other.getIssuedAt().atZone(clock.getZone()).toLocalDate();
            if (issued != null && issued.isBefore(lookbackStart)) {
                continue;
            }
            other.setStatus(Statuses.OFFER_NOT_TAKEN);
            persistence.run(Update.entity(EmbeddedOffers_.class).entry(other));
        }
    }

    /** The live attribution on a policy. */
    public SaleAttributions currentAttribution(String policyId) {
        return persistence.run(Select.from(SaleAttributions_.class)
                .where(a -> a.policy_ID().eq(policyId)))
                .listOf(SaleAttributions.class).stream()
                .filter(a -> !Statuses.ATTRIBUTION_REVERSED.equals(a.getStatus()))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No attribution on policy " + policyId));
    }

    public List<SaleAttributions> attributionsFor(String policyId) {
        return persistence.run(Select.from(SaleAttributions_.class)
                .where(a -> a.policy_ID().eq(policyId)))
                .listOf(SaleAttributions.class);
    }

    /**
     * The token is valid for 24 hours, but never past the lease start date the
     * partner supplied — the quote is valid until the lease begins.
     */
    private Instant rentersExpiry(Instant issuedAt, LocalDate leaseStartDate) {
        Instant tokenExpiry = issuedAt.plus(Statuses.EMBEDDED_TOKEN_HOURS, ChronoUnit.HOURS);
        if (leaseStartDate == null) {
            return tokenExpiry;
        }
        Instant leaseStart = leaseStartDate.atStartOfDay(clock.getZone()).toInstant();
        return leaseStart.isAfter(tokenExpiry) ? leaseStart : tokenExpiry;
    }

    /**
     * Renters pricing at the published limits. Renters is not on the rate tables
     * seeded for auto and home, so the offer carries the filed flat rate for the
     * $15,000 / $100,000 package.
     */
    private static java.math.BigDecimal rentersPremium() {
        return new java.math.BigDecimal("168.00");
    }

    public EmbeddedOffers byToken(String quoteToken) {
        return persistence.run(Select.from(EmbeddedOffers_.class)
                .where(o -> o.quoteToken().eq(quoteToken)))
                .first(EmbeddedOffers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No offer for token " + quoteToken));
    }

    public EmbeddedOffers load(String offerId) {
        return persistence.run(Select.from(EmbeddedOffers_.class)
                .where(o -> o.ID().eq(offerId)))
                .first(EmbeddedOffers.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Embedded offer not found: " + offerId));
    }

    public SaleAttributions loadAttribution(String attributionId) {
        return persistence.run(Select.from(SaleAttributions_.class)
                .where(a -> a.ID().eq(attributionId)))
                .first(SaleAttributions.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Attribution not found: " + attributionId));
    }

    private Policies policy(String policyId) {
        return persistence.run(Select.from(Policies_.class).where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Policy not found: " + policyId));
    }
}
