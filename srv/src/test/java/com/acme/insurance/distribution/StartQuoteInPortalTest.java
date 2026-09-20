package com.acme.insurance.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.support.DistributionTestBase;

import cds.gen.insurance.Agencies;
import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.PortalQuotes;
import cds.gen.insurance.Producers;

/**
 * Spec: specs/distribution/agent-portal/start-quote-in-portal.feature.md
 * "Duplicate quote on the same risk is merged", "Prior carrier information
 * drives the transfer discount", and "Saved quote expires after 30 days" are
 * [proposed] and intentionally absent.
 */
@DisplayName("Start a Quote in the Agent Portal — specs/distribution/agent-portal/start-quote-in-portal.feature.md")
class StartQuoteInPortalTest extends DistributionTestBase {

    @Test
    @DisplayName("Producer quotes personal auto from the portal @v1")
    void producerQuotesPersonalAutoFromThePortal() {
        // An appointed producer signed in to the agent portal
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "5500101", "OH");

        // The producer enters a 2021 Honda CR-V, one licensed driver, and a
        // Columbus garaging ZIP code
        PortalQuotes quote = db(() -> portalQuoting.startAutoQuote(producer.getId(),
                "Honda", "CR-V", 2021, "Jordan Rivera", "jordan.rivera@example.com",
                "+1-555-0142", "OH", "43215", true));

        assertThat(quote.getStatus()).isEqualTo(Statuses.PORTAL_QUOTE_SAVED);

        // A six-month premium is returned for each available plan
        AutoQuotes rated = db(() -> quotingManagerQuote(quote.getAutoQuoteId()));
        assertThat(rated.getTermMonths()).isEqualTo(6);
        assertThat(rated.getOptions()).isNotEmpty();
        assertThat(rated.getOptions()).allSatisfy(option ->
                assertThat(option.getPremium()).isNotNull());

        // The quote is saved under the producer's agency code
        assertThat(quote.getAgencyId()).isEqualTo(agency.getId());
        assertThat(quote.getProducerId()).isEqualTo(producer.getId());
    }

    @Test
    @DisplayName("Quote outside the producer's state authority is refused @v1")
    void quoteOutsideTheProducersStateAuthorityIsRefused() {
        // A producer appointed only in Ohio
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "5500102", "OH");

        // The producer enters a garaging address in Louisville, Kentucky
        PortalQuotes refused = db(() -> portalQuoting.startAutoQuote(producer.getId(),
                "Honda", "CR-V", 2021, "Jordan Rivera", "jordan.rivera@example.com",
                "+1-555-0142", "KY", "40202", true));

        // The quote is not generated
        assertThat(refused.getStatus()).isEqualTo(Statuses.PORTAL_QUOTE_REFUSED);
        assertThat(refused.getAutoQuoteId()).isNull();

        // The producer is told a Kentucky appointment is required
        assertThat(refused.getRefusalReason()).contains("KY appointment is required");
    }

    @Test
    @DisplayName("Motor vehicle report order is disclosed to the applicant @v1")
    void motorVehicleReportOrderIsDisclosedToTheApplicant() {
        // A quote that requires ordering a motor vehicle report
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_PERSONAL_AUTO);
        Producers producer = newLicensedProducer(agency.getId(), "5500103", "OH");

        // The producer requests the rated premium without consent on file
        assertThatThrownBy(() -> db(() -> portalQuoting.startAutoQuote(producer.getId(),
                "Honda", "CR-V", 2021, "Jordan Rivera", "jordan.rivera@example.com",
                "+1-555-0142", "OH", "43215", false)))
                // The applicant's consent is captured before the order is placed
                .hasMessageContaining("consent to the motor vehicle report order must be")
                .hasMessageContaining("before the order is placed");

        // With consent captured, the quote rates
        PortalQuotes quote = db(() -> portalQuoting.startAutoQuote(producer.getId(),
                "Honda", "CR-V", 2021, "Jordan Rivera", "jordan.rivera@example.com",
                "+1-555-0142", "OH", "43215", true));

        // The consent record is retained with the quote
        assertThat(quote.getMvrConsentCaptured()).isTrue();
        assertThat(quote.getMvrConsentOn()).isEqualTo(BASE_DATE);
    }

    @Test
    @DisplayName("Roof age over 20 years routes a homeowners quote to review @v1")
    void roofAgeOver20YearsRoutesAHomeownersQuoteToReview() {
        // A homeowners quote on a dwelling with a 24-year-old composition
        // shingle roof
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_HOMEOWNERS);
        Producers producer = newLicensedProducer(agency.getId(), "5500104", "OH");

        // The producer submits for rating
        PortalQuotes quote = db(() -> portalQuoting.startHomeQuote(producer.getId(),
                "88 Maple Avenue, Columbus OH", 1998, "Frame", "Avery Chen",
                "avery.chen@example.com", new BigDecimal("300000.00"), "OH", 24));

        // An indicative premium is shown marked "subject to inspection"
        assertThat(quote.getStatus())
                .isEqualTo(Statuses.PORTAL_QUOTE_SUBJECT_TO_INSPECTION);
        assertThat(quote.getSubjectToInspection()).isTrue();
        assertThat(quote.getIndicativePremium()).isNotNull();

        // A four-point inspection is ordered before the quote can be bound
        assertThat(quote.getInspectionOrdered()).isTrue();
    }

    @Test
    @DisplayName("Roof age over 20 years routes a homeowners quote to review @v1 — a newer roof rates normally")
    void newerRoofDoesNotRouteToReview() {
        Agencies agency = newAppointedAgency("OH", Statuses.LINE_HOMEOWNERS);
        Producers producer = newLicensedProducer(agency.getId(), "5500105", "OH");

        // A 12-year-old roof is inside the 20-year threshold
        PortalQuotes quote = db(() -> portalQuoting.startHomeQuote(producer.getId(),
                "90 Maple Avenue, Columbus OH", 2010, "Frame", "Avery Chen",
                "avery.chen@example.com", new BigDecimal("300000.00"), "OH", 12));

        assertThat(quote.getStatus()).isEqualTo(Statuses.PORTAL_QUOTE_SAVED);
        assertThat(quote.getSubjectToInspection()).isFalse();
        assertThat(quote.getInspectionOrdered()).isFalse();
    }

    private AutoQuotes quotingManagerQuote(String quoteId) {
        return persistence.run(com.sap.cds.ql.Select
                .from(cds.gen.insurance.AutoQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(AutoQuotes.class);
    }
}
