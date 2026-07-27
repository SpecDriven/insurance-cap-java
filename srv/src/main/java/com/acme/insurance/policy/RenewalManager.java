package com.acme.insurance.policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Rates;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoPlanRates;
import cds.gen.insurance.Installments;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.RenewalOffers;
import cds.gen.insurance.RenewalOffers_;

/**
 * Spec: specs/policy/auto-policy/renew-policy.feature.md
 */
@Component
public class RenewalManager {

    // "Automatic renewal offer": policies expiring in 45 days
    private static final int RENEWAL_LEAD_DAYS = 45;

    private final PersistenceService persistence;
    private final Rates rates;
    private final EmailPort email;

    public RenewalManager(PersistenceService persistence, Rates rates, EmailPort email) {
        this.persistence = persistence;
        this.rates = rates;
        this.email = email;
    }

    /**
     * "Automatic renewal offer": for every in-force auto policy expiring in 45
     * days whose account has no unpaid balance, generate an offer at current
     * rates and send it to the named insured. Invoked by the nightly job.
     */
    public List<RenewalOffers> generateOffers(LocalDate today) {
        LocalDate expiringOn = today.plusDays(RENEWAL_LEAD_DAYS);
        List<Policies> expiring = persistence.run(Select.from(Policies_.class)
                .where(p -> p.status().eq(Statuses.POLICY_IN_FORCE)
                        .and(p.lineOfBusiness().eq(Statuses.LOB_AUTO))
                        .and(p.expirationDate().eq(expiringOn)))
                .columns(p -> p._all(), p -> p.installments().expand()))
                .listOf(Policies.class);

        List<RenewalOffers> offers = new ArrayList<>();
        for (Policies policy : expiring) {
            if (hasUnpaidBalance(policy, today) || offerAlreadyExists(policy.getId())) {
                continue;
            }

            // "at current rates" — re-rated from the rating table, not the bound premium
            AutoPlanRates currentRate = rates.autoPlanRate(policy.getPlan());

            RenewalOffers offer = RenewalOffers.create();
            offer.setId(UUID.randomUUID().toString());
            offer.setPolicyId(policy.getId());
            offer.setOfferedOn(today);
            offer.setNewEffectiveDate(policy.getExpirationDate());
            offer.setNewExpirationDate(policy.getExpirationDate().plusMonths(policy.getTermMonths()));
            offer.setPlan(policy.getPlan());
            offer.setPremium(currentRate.getSixMonthPremium());
            offer.setStatus(Statuses.OFFER_OFFERED);
            persistence.run(Insert.into(RenewalOffers_.class).entry(offer));

            email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                    "Your renewal offer — policy " + policy.getPolicyNumber(),
                    "Your " + policy.getPlan() + " auto policy renews on " + offer.getNewEffectiveDate()
                            + ". The renewal premium at current rates is $"
                            + offer.getPremium().toPlainString()
                            + " for the next " + policy.getTermMonths() + " months."));
            offers.add(offer);
        }
        return offers;
    }

    private boolean hasUnpaidBalance(Policies policy, LocalDate today) {
        List<Installments> installments = policy.getInstallments();
        if (installments == null) {
            return false;
        }
        return installments.stream().anyMatch(i ->
                Statuses.INSTALLMENT_PAST_DUE.equals(i.getStatus())
                        || (Statuses.INSTALLMENT_SCHEDULED.equals(i.getStatus())
                                && i.getDueDate() != null && i.getDueDate().isBefore(today)));
    }

    private boolean offerAlreadyExists(String policyId) {
        return persistence.run(Select.from(RenewalOffers_.class)
                .where(o -> o.policy_ID().eq(policyId)))
                .rowCount() > 0;
    }
}
