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
import cds.gen.insurance.EmbeddedOffers;
import cds.gen.insurance.Partners;
import cds.gen.insurance.Policies;
import cds.gen.insurance.SaleAttributions;

/**
 * Spec: specs/distribution/embedded/attribute-embedded-sale.feature.md
 * "Cancelled policy reverses partner credit" and "Customer who abandons and
 * returns directly keeps partner credit" are [proposed] and intentionally
 * absent.
 */
@DisplayName("Attribute an Embedded Sale — specs/distribution/embedded/attribute-embedded-sale.feature.md")
class AttributeEmbeddedSaleTest extends DistributionTestBase {

    private static final LocalDate JUNE_1_2026 = LocalDate.of(2026, 6, 1);

    @Test
    @DisplayName("Policy bound from a partner token carries the partner code @v1")
    void policyBoundFromAPartnerTokenCarriesThePartnerCode() {
        // A bind request presenting a quote token issued to partner LEASEHUB
        Agencies agencyOfRecord = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners partner = certifiedPartner("LEASEHUB20", agencyOfRecord);
        EmbeddedOffers offer = db(() -> offers.offerRenters(partner.getPartnerCode(),
                "Avery Chen", "Unit 4B, 220 Elm Street, Columbus OH", JUNE_1_2026));

        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("168.00"),
                BASE_DATE, null);

        try {
            // The policy is bound
            SaleAttributions attribution = db(() -> offers.bindFromToken(
                    offer.getQuoteToken(), policy.getId()));

            // The partner code LEASEHUB is stamped on the policy
            assertThat(attribution.getPartnerCode()).isEqualTo("LEASEHUB20");
            assertThat(db(() -> reloadPolicy(policy.getId())).getPartnerCode())
                    .isEqualTo("LEASEHUB20");

            // The licensed agency of record for that partner is recorded as producer
            assertThat(attribution.getAgencyOfRecordId()).isEqualTo(agencyOfRecord.getId());
            assertThat(db(() -> reloadPolicy(policy.getId())).getProducingAgencyId())
                    .isEqualTo(agencyOfRecord.getId());
            assertThat(attribution.getStatus()).isEqualTo(Statuses.ATTRIBUTION_ATTRIBUTED);
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Last touch wins when two partners present the same risk @v1")
    void lastTouchWinsWhenTwoPartnersPresentTheSameRisk() {
        // Embedded offers from two partners for the same applicant within the
        // look-back window
        Agencies firstAgency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Agencies secondAgency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners first = certifiedPartner("LEASEHUB21", firstAgency);
        Partners second = certifiedPartner("DEALERX21", secondAgency);

        EmbeddedOffers firstOffer = db(() -> offers.offerRenters(first.getPartnerCode(),
                "Jordan Rivera", "Unit 9A, 14 Oak Street, Columbus OH", JUNE_1_2026));
        EmbeddedOffers secondOffer = db(() -> offers.offerRenters(second.getPartnerCode(),
                "Jordan Rivera", "Unit 9A, 14 Oak Street, Columbus OH", JUNE_1_2026));

        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("168.00"),
                BASE_DATE, null);

        try {
            // The applicant binds using the second partner's token
            SaleAttributions attribution = db(() -> offers.bindFromToken(
                    secondOffer.getQuoteToken(), policy.getId()));

            // The second partner receives full attribution
            assertThat(attribution.getPartnerCode()).isEqualTo("DEALERX21");
            assertThat(attribution.getPartnerId()).isEqualTo(second.getId());
            assertThat(attribution.getAgencyOfRecordId()).isEqualTo(secondAgency.getId());

            // The first partner's offer is closed as not taken
            assertThat(db(() -> offers.load(firstOffer.getId())).getStatus())
                    .isEqualTo(Statuses.OFFER_NOT_TAKEN);
        } finally {
            deletePolicies(policy.getId());
        }
    }

    @Test
    @DisplayName("Attribution is frozen once the first commission is paid @v1")
    void attributionIsFrozenOnceTheFirstCommissionIsPaid() {
        // A policy whose first commission has been paid to partner LEASEHUB
        Agencies leasehubAgency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Agencies otherAgency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Partners leasehub = certifiedPartner("LEASEHUB22", leasehubAgency);
        certifiedPartner("DEALERX22", otherAgency);

        EmbeddedOffers offer = db(() -> offers.offerRenters(leasehub.getPartnerCode(),
                "Sam Ito", "Unit 2C, 8 Pine Street, Columbus OH", JUNE_1_2026));
        Policies policy = insertPaidInFullAutoPolicy(new BigDecimal("168.00"),
                BASE_DATE, null);

        try {
            SaleAttributions original = db(() -> offers.bindFromToken(
                    offer.getQuoteToken(), policy.getId()));
            SaleAttributions paid = db(() -> offers.markCommissionPaid(original.getId()));
            assertThat(paid.getFrozen()).isTrue();

            // A correction request seeks to reattribute the policy
            assertThatThrownBy(() -> db(() -> offers.reattribute(policy.getId(),
                    "DEALERX22", null)))
                    // The change requires distribution management approval
                    .hasMessageContaining("frozen once the first commission is paid")
                    .hasMessageContaining("distribution management approval is required");

            // With approval, the correction goes through
            List<SaleAttributions> corrected = db(() -> offers.reattribute(policy.getId(),
                    "DEALERX22", "Dana Whitfield, Distribution"));

            // Any resulting adjustment is made as a chargeback and re-credit,
            // not a silent edit: the original row is reversed and preserved
            assertThat(corrected).hasSize(2);
            SaleAttributions reversed = corrected.stream()
                    .filter(a -> original.getId().equals(a.getId()))
                    .findFirst().orElseThrow();
            SaleAttributions recredit = corrected.stream()
                    .filter(a -> !original.getId().equals(a.getId()))
                    .findFirst().orElseThrow();

            assertThat(reversed.getStatus()).isEqualTo(Statuses.ATTRIBUTION_REVERSED);
            assertThat(reversed.getPartnerCode()).isEqualTo("LEASEHUB22");
            assertThat(reversed.getSupersededById()).isEqualTo(recredit.getId());
            assertThat(reversed.getApprovedBy()).isEqualTo("Dana Whitfield, Distribution");

            assertThat(recredit.getStatus()).isEqualTo(Statuses.ATTRIBUTION_REATTRIBUTED);
            assertThat(recredit.getPartnerCode()).isEqualTo("DEALERX22");

            // Both rows remain on the policy for audit
            assertThat(db(() -> offers.attributionsFor(policy.getId()))).hasSize(2);
            assertThat(db(() -> reloadPolicy(policy.getId())).getPartnerCode())
                    .isEqualTo("DEALERX22");
        } finally {
            deletePolicies(policy.getId());
        }
    }

    private Partners certifiedPartner(String partnerCode, Agencies agencyOfRecord) {
        Partners partner = insertPartner(partnerCode, agencyOfRecord.getId(), null);
        return db(() -> partners.certify(partner.getId(), "renters, personal auto",
                "Integration Manager"));
    }
}
