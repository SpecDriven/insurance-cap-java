package com.acme.insurance.quoting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Rates;
import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoPlanRates;
import cds.gen.insurance.AutoQuoteOptions;
import cds.gen.insurance.AutoQuotes;
import cds.gen.insurance.AutoQuotes_;
import cds.gen.insurance.HomeQuoteOptions;
import cds.gen.insurance.HomeQuotes;
import cds.gen.insurance.HomeQuotes_;
import cds.gen.insurance.HomeRates;

/**
 * Spec: specs/policy/auto-policy/quote-policy.feature.md
 * Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
 */
@Component
public class QuotingManager {

    private static final int AUTO_TERM_MONTHS = 6;

    // "Decline high-risk driver": 3 or more at-fault accidents in the past 3 years
    private static final int HIGH_RISK_AT_FAULT_ACCIDENTS = 3;

    private final PersistenceService persistence;
    private final Rates rates;

    public QuotingManager(PersistenceService persistence, Rates rates) {
        this.persistence = persistence;
        this.rates = rates;
    }

    /**
     * "Basic auto quote": a six-month premium per available plan.
     * "Decline high-risk driver": no quote is offered; the decline reason is
     * recorded on the declined quote. The driving record arrives as the
     * at-fault accident count of the prospect's MVR for the past 3 years.
     */
    public AutoQuotes requestAutoQuote(String vehicleMake, String vehicleModel, Integer vehicleYear,
            String driverName, String driverEmail, String driverPhone, Integer atFaultAccidents3y,
            String garagingZip) {

        AutoQuotes quote = AutoQuotes.create();
        quote.setId(UUID.randomUUID().toString());
        quote.setVehicleMake(vehicleMake);
        quote.setVehicleModel(vehicleModel);
        quote.setVehicleYear(vehicleYear);
        quote.setDriverName(driverName);
        quote.setDriverEmail(driverEmail);
        quote.setDriverPhone(driverPhone);
        quote.setAtFaultAccidents3y(atFaultAccidents3y);
        quote.setGaragingZip(garagingZip);
        quote.setTermMonths(AUTO_TERM_MONTHS);

        int atFault = atFaultAccidents3y == null ? 0 : atFaultAccidents3y;
        if (atFault >= HIGH_RISK_AT_FAULT_ACCIDENTS) {
            quote.setStatus(Statuses.QUOTE_DECLINED);
            quote.setDeclineReason(Statuses.DECLINE_REASON_UNDERWRITING);
        } else {
            quote.setStatus(Statuses.QUOTE_ACTIVE);
            List<AutoQuoteOptions> options = new ArrayList<>();
            for (AutoPlanRates rate : rates.allAutoPlanRates()) {
                AutoQuoteOptions option = AutoQuoteOptions.create();
                option.setId(UUID.randomUUID().toString());
                option.setPlan(rate.getPlan());
                option.setBodilyInjuryLimit(rate.getBodilyInjuryLimit());
                option.setCollisionDeductible(rate.getCollisionDeductible());
                option.setPremium(rate.getSixMonthPremium());
                option.setDisplayOrder(rate.getDisplayOrder());
                options.add(option);
            }
            quote.setOptions(options);
        }

        persistence.run(Insert.into(AutoQuotes_.class).entry(quote));
        return readAutoQuote(quote.getId());
    }

    public AutoQuotes readAutoQuote(String quoteId) {
        return persistence.run(Select.from(AutoQuotes_.class)
                .where(q -> q.ID().eq(quoteId))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(AutoQuotes.class);
    }

    /** "Basic homeowners quote": an annual premium per available deductible. */
    public HomeQuotes requestHomeQuote(String address, Integer yearBuilt, String constructionType,
            String ownerName, String ownerEmail, BigDecimal dwellingCoverage) {

        HomeQuotes quote = HomeQuotes.create();
        quote.setId(UUID.randomUUID().toString());
        quote.setStatus(Statuses.QUOTE_ACTIVE);
        quote.setAddress(address);
        quote.setYearBuilt(yearBuilt);
        quote.setConstructionType(constructionType);
        quote.setOwnerName(ownerName);
        quote.setOwnerEmail(ownerEmail);
        quote.setDwellingCoverage(dwellingCoverage);

        List<HomeQuoteOptions> options = new ArrayList<>();
        for (HomeRates rate : rates.homeRates(dwellingCoverage)) {
            HomeQuoteOptions option = HomeQuoteOptions.create();
            option.setId(UUID.randomUUID().toString());
            option.setDeductible(rate.getDeductible());
            option.setAnnualPremium(rate.getAnnualPremium());
            option.setDisplayOrder(rate.getDisplayOrder());
            options.add(option);
        }
        quote.setOptions(options);

        persistence.run(Insert.into(HomeQuotes_.class).entry(quote));
        return persistence.run(Select.from(HomeQuotes_.class)
                .where(q -> q.ID().eq(quote.getId()))
                .columns(q -> q._all(), q -> q.options().expand()))
                .single(HomeQuotes.class);
    }
}
