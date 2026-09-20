package com.acme.insurance.distribution;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.quoting.QuotingManager;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoQuoteOptions;
import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.HomeQuoteOptions;
import cds.gen.insurance.HomeQuotes;
import cds.gen.insurance.PortalQuotes;
import cds.gen.insurance.PortalQuotes_;
import cds.gen.insurance.Producers;

/**
 * Quoting from the agent portal: the producer's state authority is enforced
 * before rating, consent is captured before ordering a motor vehicle report,
 * and an aged roof routes a homeowners quote to inspection.
 *
 * Spec: specs/distribution/agent-portal/start-quote-in-portal.feature.md
 */
@Component
public class PortalQuotingManager {

    private final PersistenceService persistence;
    private final QuotingManager quoting;
    private final ProducerLicensingManager licensing;
    private final AppointmentManager appointments;
    private final Clock clock;

    public PortalQuotingManager(PersistenceService persistence, QuotingManager quoting,
            ProducerLicensingManager licensing, AppointmentManager appointments, Clock clock) {
        this.persistence = persistence;
        this.quoting = quoting;
        this.licensing = licensing;
        this.appointments = appointments;
        this.clock = clock;
    }

    /**
     * "Producer quotes personal auto from the portal" — a 2021 Honda CR-V with
     * one licensed driver and a Columbus garaging ZIP returns a six-month
     * premium for each available plan, saved under the producer's agency code.
     *
     * "Quote outside the producer's state authority is refused" — a producer
     * appointed only in Ohio entering a Kentucky garaging address gets no quote
     * and is told a Kentucky appointment is required.
     *
     * "Motor vehicle report order is disclosed to the applicant" — the
     * applicant's consent to the report order is captured before the order is
     * placed, and the consent record is retained with the quote.
     */
    public PortalQuotes startAutoQuote(String producerId, String vehicleMake, String vehicleModel,
            Integer vehicleYear, String driverName, String driverEmail, String driverPhone,
            String garagingState, String garagingZip, Boolean mvrConsent) {

        Producers producer = licensing.load(producerId);
        LocalDate today = LocalDate.now(clock);

        PortalQuotes refused = refuseIfOutsideAuthority(producer, garagingState, garagingZip,
                Statuses.LOB_AUTO, today);
        if (refused != null) {
            return refused;
        }

        // The report is ordered as part of rating, so consent is captured first.
        if (!Boolean.TRUE.equals(mvrConsent)) {
            throw new ServiceException(ErrorStatuses.CONFLICT,
                    "The applicant's consent to the motor vehicle report order must be "
                            + "captured before the order is placed.");
        }

        AutoQuotes quote = quoting.requestAutoQuote(vehicleMake, vehicleModel, vehicleYear,
                driverName, driverEmail, driverPhone, 0, garagingZip);

        PortalQuotes portalQuote = newPortalQuote(producer, Statuses.LOB_AUTO, garagingState,
                garagingZip, today);
        portalQuote.setAutoQuoteId(quote.getId());
        portalQuote.setStatus(Statuses.PORTAL_QUOTE_SAVED);
        portalQuote.setMvrConsentCaptured(true);
        portalQuote.setMvrConsentOn(today);
        portalQuote.setIndicativePremium(lowestAutoPremium(quote));
        persistence.run(Insert.into(PortalQuotes_.class).entry(portalQuote));

        return load(portalQuote.getId());
    }

    /**
     * "Roof age over 20 years routes a homeowners quote to review" — a 24-year-old
     * composition shingle roof returns an indicative premium marked "subject to
     * inspection", and a four-point inspection is ordered before the quote can be
     * bound.
     */
    public PortalQuotes startHomeQuote(String producerId, String address, Integer yearBuilt,
            String constructionType, String ownerName, String ownerEmail,
            BigDecimal dwellingCoverage, String garagingState, Integer roofAgeYears) {

        Producers producer = licensing.load(producerId);
        LocalDate today = LocalDate.now(clock);

        PortalQuotes refused = refuseIfOutsideAuthority(producer, garagingState, null,
                Statuses.LOB_HOME, today);
        if (refused != null) {
            return refused;
        }

        HomeQuotes quote = quoting.requestHomeQuote(address, yearBuilt, constructionType,
                ownerName, ownerEmail, dwellingCoverage);

        PortalQuotes portalQuote = newPortalQuote(producer, Statuses.LOB_HOME, garagingState,
                null, today);
        portalQuote.setHomeQuoteId(quote.getId());
        portalQuote.setRoofAgeYears(roofAgeYears);
        portalQuote.setIndicativePremium(lowestHomePremium(quote));

        boolean agedRoof = roofAgeYears != null
                && roofAgeYears > Statuses.ROOF_AGE_INSPECTION_YEARS;
        if (agedRoof) {
            // The premium shown is indicative only until the inspection is back.
            portalQuote.setStatus(Statuses.PORTAL_QUOTE_SUBJECT_TO_INSPECTION);
            portalQuote.setSubjectToInspection(true);
            portalQuote.setInspectionOrdered(true);
        } else {
            portalQuote.setStatus(Statuses.PORTAL_QUOTE_SAVED);
        }
        persistence.run(Insert.into(PortalQuotes_.class).entry(portalQuote));

        return load(portalQuote.getId());
    }

    /**
     * A producer may quote only where the agency holds an active appointment.
     * Returns a refused portal quote, or null when the producer is authorized.
     */
    private PortalQuotes refuseIfOutsideAuthority(Producers producer, String state,
            String zip, String lineOfBusiness, LocalDate today) {
        if (state == null) {
            return null;
        }
        boolean appointed = producer.getAgencyId() != null
                && !appointments.activeFor(producer.getAgencyId(), state).isEmpty();
        if (appointed) {
            return null;
        }

        PortalQuotes refusedQuote = newPortalQuote(producer, lineOfBusiness, state, zip, today);
        refusedQuote.setStatus(Statuses.PORTAL_QUOTE_REFUSED);
        refusedQuote.setRefusalReason("A " + state + " appointment is required to quote in "
                + state + ".");
        persistence.run(Insert.into(PortalQuotes_.class).entry(refusedQuote));
        return load(refusedQuote.getId());
    }

    private PortalQuotes newPortalQuote(Producers producer, String lineOfBusiness, String state,
            String zip, LocalDate today) {
        PortalQuotes portalQuote = PortalQuotes.create();
        portalQuote.setId(UUID.randomUUID().toString());
        portalQuote.setProducerId(producer.getId());
        // The quote is saved under the producer's agency code.
        portalQuote.setAgencyId(producer.getAgencyId());
        portalQuote.setLineOfBusiness(lineOfBusiness);
        portalQuote.setGaragingState(state);
        portalQuote.setGaragingZip(zip);
        portalQuote.setSavedOn(today);
        return portalQuote;
    }

    private static BigDecimal lowestAutoPremium(AutoQuotes quote) {
        return quote.getOptions() == null ? null
                : quote.getOptions().stream()
                        .map(AutoQuoteOptions::getPremium)
                        .filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null);
    }

    private static BigDecimal lowestHomePremium(HomeQuotes quote) {
        return quote.getOptions() == null ? null
                : quote.getOptions().stream()
                        .map(HomeQuoteOptions::getAnnualPremium)
                        .filter(java.util.Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null);
    }

    public PortalQuotes load(String portalQuoteId) {
        return persistence.run(Select.from(PortalQuotes_.class)
                .where(q -> q.ID().eq(portalQuoteId)))
                .first(PortalQuotes.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Portal quote not found: " + portalQuoteId));
    }
}
