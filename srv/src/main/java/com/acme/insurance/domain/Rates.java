package com.acme.insurance.domain;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoPlanRates;
import cds.gen.insurance.AutoPlanRates_;
import cds.gen.insurance.BillingPlans;
import cds.gen.insurance.BillingPlans_;
import cds.gen.insurance.HomeRates;
import cds.gen.insurance.HomeRates_;

/**
 * Read access to the rating tables. Rates live in CSV seed data under
 * db/data/ (ARCHITECTURE.md "Rate data"), never in code.
 */
@Component
public class Rates {

    private final PersistenceService persistence;

    public Rates(PersistenceService persistence) {
        this.persistence = persistence;
    }

    public List<AutoPlanRates> allAutoPlanRates() {
        return persistence.run(Select.from(AutoPlanRates_.class)
                .orderBy(r -> r.displayOrder().asc()))
                .listOf(AutoPlanRates.class);
    }

    public AutoPlanRates autoPlanRate(String plan) {
        return persistence.run(Select.from(AutoPlanRates_.class)
                .where(r -> r.plan().eq(plan)))
                .first(AutoPlanRates.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Unknown auto plan '" + plan + "'"));
    }

    public List<HomeRates> homeRates(BigDecimal dwellingCoverage) {
        return persistence.run(Select.from(HomeRates_.class)
                .where(r -> r.dwellingCoverage().eq(dwellingCoverage))
                .orderBy(r -> r.displayOrder().asc()))
                .listOf(HomeRates.class);
    }

    public BillingPlans billingPlan(String plan) {
        return persistence.run(Select.from(BillingPlans_.class)
                .where(p -> p.plan().eq(plan)))
                .first(BillingPlans.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.BAD_REQUEST,
                        "Unknown billing plan '" + plan + "'"));
    }
}
