package com.acme.insurance.handlers;

import org.springframework.stereotype.Component;

import com.acme.insurance.quoting.QuotingManager;
import com.sap.cds.Struct;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;

import cds.gen.quoteservice.QuoteService_;
import cds.gen.quoteservice.RequestAutoQuoteContext;
import cds.gen.quoteservice.RequestHomeQuoteContext;

/**
 * Spec: specs/policy/auto-policy/quote-policy.feature.md
 * Spec: specs/policy/home-policy/quote-homeowners-policy.feature.md
 */
@Component
@ServiceName(QuoteService_.CDS_NAME)
public class QuoteServiceHandler implements EventHandler {

    private final QuotingManager quoting;

    public QuoteServiceHandler(QuotingManager quoting) {
        this.quoting = quoting;
    }

    @On(event = RequestAutoQuoteContext.CDS_NAME)
    public void onRequestAutoQuote(RequestAutoQuoteContext context) {
        var quote = quoting.requestAutoQuote(
                context.getVehicleMake(), context.getVehicleModel(), context.getVehicleYear(),
                context.getDriverName(), context.getDriverEmail(), context.getDriverPhone(),
                context.getAtFaultAccidents3y(), context.getGaragingZip());
        context.setResult(Struct.access(quote).as(cds.gen.quoteservice.AutoQuotes.class));
        context.setCompleted();
    }

    @On(event = RequestHomeQuoteContext.CDS_NAME)
    public void onRequestHomeQuote(RequestHomeQuoteContext context) {
        var quote = quoting.requestHomeQuote(
                context.getAddress(), context.getYearBuilt(), context.getConstructionType(),
                context.getOwnerName(), context.getOwnerEmail(), context.getDwellingCoverage());
        context.setResult(Struct.access(quote).as(cds.gen.quoteservice.HomeQuotes.class));
        context.setCompleted();
    }
}
