package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.EmbeddedOffers;
import cds.gen.insurance.Partners;

/**
 * Spec: specs/distribution/embedded/offer-embedded-quote.feature.md
 * "Dealership passes a vehicle identification number for an auto quote",
 * "Partner outage does not block the partner's own checkout", and "Risk outside
 * appetite returns a graceful no-offer" are [proposed] and intentionally absent.
 */
@DisplayName("Offer an Embedded Quote at Point of Sale — specs/distribution/embedded/offer-embedded-quote.feature.md")
class OfferEmbeddedQuoteTest extends DistributionTestBase {

    private static final LocalDate JUNE_1_2026 = LocalDate.of(2026, 6, 1);

    @Test
    @DisplayName("Renters quote is offered inside a leasing checkout @v1")
    void rentersQuoteIsOfferedInsideALeasingCheckout() {
        // A leasing portal passing the applicant's name, unit address, and lease
        // start date of 1 June 2026
        Partners partner = certifiedPartner("LEASEHUB10");

        // The partner requests an embedded quote
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", JUNE_1_2026));

        // A renters premium is returned with $15,000 personal property and
        // $100,000 liability
        assertThat(offer.getLineOfBusiness()).isEqualTo(Statuses.LINE_RENTERS);
        assertThat(offer.getPremium()).isNotNull().isPositive();
        assertThat(offer.getPersonalPropertyLimit()).isEqualByComparingTo("15000.00");
        assertThat(offer.getLiabilityLimit()).isEqualByComparingTo("100000.00");

        // The quote is valid until the lease start date
        assertThat(offer.getExpiresAt())
                .isEqualTo(JUNE_1_2026.atStartOfDay(clock.getZone()).toInstant());
    }

    @Test
    @DisplayName("Partner may not see or alter rating factors @v1")
    void partnerMayNotSeeOrAlterRatingFactors() {
        // An embedded quote returned to a partner
        Partners partner = certifiedPartner("LEASEHUB11");
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", JUNE_1_2026));

        // The partner inspects the response payload: only the premium, coverage
        // summary, and a quote token are present
        assertThat(offer.getPremium()).isNotNull();
        assertThat(offer.getCoverageSummary()).isNotBlank();
        assertThat(offer.getQuoteToken()).startsWith("QT-");

        // No rating factors, tier assignments, or credit-based attributes are
        // disclosed — the offer record carries no such fields at all
        assertThat(offer.keySet())
                .noneSatisfy(field -> assertThat(field.toLowerCase())
                        .containsAnyOf("rating", "tier", "score", "credit"));
    }

    @Test
    @DisplayName("Required disclosures are shown before the customer accepts @v1")
    void requiredDisclosuresAreShownBeforeTheCustomerAccepts() {
        // An embedded renters offer displayed in a partner checkout
        Partners partner = certifiedPartner("LEASEHUB12");
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", JUNE_1_2026));

        // The customer moves to accept coverage before anything is acknowledged
        assertThatThrownBy(() -> db(() -> offers.accept(offer.getId())))
                // Acceptance is not recorded until both are acknowledged
                .hasMessageContaining("producer disclosure and the coverage summary must")
                .hasMessageContaining("before acceptance is recorded");
        assertThat(db(() -> offers.load(offer.getId())).getStatus())
                .isEqualTo(Statuses.OFFER_OPEN);

        // Only the disclosure acknowledged is still not enough
        db(() -> offers.acknowledgeDisclosures(offer.getId(), true, false));
        assertThatThrownBy(() -> db(() -> offers.accept(offer.getId())))
                .hasMessageContaining("before acceptance is recorded");

        // The state-required producer disclosure and the coverage summary are
        // shown in the partner's flow
        EmbeddedOffers acknowledged =
                db(() -> offers.acknowledgeDisclosures(offer.getId(), true, true));
        assertThat(acknowledged.getDisclosureShown()).isTrue();
        assertThat(acknowledged.getCoverageSummaryShown()).isTrue();

        EmbeddedOffers accepted = db(() -> offers.accept(offer.getId()));
        assertThat(accepted.getStatus()).isEqualTo(Statuses.OFFER_ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
    }

    @Test
    @DisplayName("Embedded quote token expires in 24 hours @v1")
    void embeddedQuoteTokenExpiresIn24Hours() {
        // A quote token issued at 14:00 on 3 May 2026
        LocalDate may3 = LocalDate.of(2026, 5, 3);
        clock.setBusinessDateTime(may3, LocalTime.of(14, 0));

        Partners partner = certifiedPartner("LEASEHUB13");
        // No lease start date, so the plain 24-hour token life applies
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", null));
        db(() -> offers.acknowledgeDisclosures(offer.getId(), true, true));

        assertThat(offer.getExpiresAt())
                .isEqualTo(offer.getIssuedAt().plusSeconds(24 * 3600));

        try {
            // The partner attempts to bind at 15:00 on 4 May 2026 — 25 hours on
            clock.setBusinessDateTime(may3.plusDays(1), LocalTime.of(15, 0));

            assertThatThrownBy(() -> db(() -> offers.accept(offer.getId())))
                    // The bind is refused as an expired token
                    .hasMessageContaining("Expired token")
                    // A fresh quote may be requested at current rates
                    .hasMessageContaining("request a fresh quote at current rates");

            assertThat(db(() -> offers.load(offer.getId())).getStatus())
                    .isEqualTo(Statuses.OFFER_EXPIRED);
        } finally {
            clock.setBusinessDate(BASE_DATE);
        }
    }

    @Test
    @DisplayName("Embedded quote token expires in 24 hours @v1 — a bind inside the window succeeds")
    void bindInsideTheTokenWindowSucceeds() {
        LocalDate may3 = LocalDate.of(2026, 5, 3);
        clock.setBusinessDateTime(may3, LocalTime.of(14, 0));

        Partners partner = certifiedPartner("LEASEHUB14");
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", null));
        db(() -> offers.acknowledgeDisclosures(offer.getId(), true, true));

        try {
            // 23 hours on, still inside the window
            clock.setBusinessDateTime(may3.plusDays(1), LocalTime.of(13, 0));
            assertThat(db(() -> offers.accept(offer.getId())).getStatus())
                    .isEqualTo(Statuses.OFFER_ACCEPTED);
        } finally {
            clock.setBusinessDate(BASE_DATE);
        }
    }

    /** A partner certified for production under a licensed agency of record. */
    private Partners certifiedPartner(String partnerCode) {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = insertPartner(partnerCode, agency.getId(), null);
        return db(() -> partners.certify(partner.getId(), "renters, personal auto",
                "Integration Manager"));
    }
}
