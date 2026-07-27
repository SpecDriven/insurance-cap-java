package com.acme.insurance.policy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.ProRata;
import com.acme.insurance.domain.Rates;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AutoPlanRates;
import cds.gen.insurance.Endorsements;
import cds.gen.insurance.Endorsements_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.PolicyVehicles;
import cds.gen.insurance.PolicyVehicles_;

/**
 * Spec: specs/policy/auto-policy/endorse-policy.feature.md
 */
@Component
public class EndorsementManager {

    private final PersistenceService persistence;
    private final Rates rates;
    private final EmailPort email;
    private final Clock clock;

    public EndorsementManager(PersistenceService persistence, Rates rates, EmailPort email, Clock clock) {
        this.persistence = persistence;
        this.rates = rates;
        this.email = email;
        this.clock = clock;
    }

    /**
     * "Add a vehicle mid-term": the added vehicle is rated at the policy's plan
     * (current rate table) and its premium is prorated over the remaining
     * months of the term; a revised declarations page is issued.
     */
    public Policies addVehicle(String policyId, String make, String model, Integer modelYear) {
        Policies policy = persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId)))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Policy not found"));

        if (!Statuses.POLICY_IN_FORCE.equals(policy.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The policy is not in force");
        }
        if (!Statuses.LOB_AUTO.equals(policy.getLineOfBusiness())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "Vehicles can only be added to auto policies");
        }

        LocalDate endorsementDate = LocalDate.now(clock);
        AutoPlanRates rate = rates.autoPlanRate(policy.getPlan());
        BigDecimal additionalPremium = ProRata.proratedForRemainingTerm(rate.getSixMonthPremium(),
                policy.getTermMonths(), policy.getEffectiveDate(), endorsementDate);

        PolicyVehicles vehicle = PolicyVehicles.create();
        vehicle.setId(UUID.randomUUID().toString());
        vehicle.setPolicyId(policyId);
        vehicle.setMake(make);
        vehicle.setModel(model);
        vehicle.setModelYear(modelYear);
        vehicle.setAddedOn(endorsementDate);
        persistence.run(Insert.into(PolicyVehicles_.class).entry(vehicle));

        Endorsements endorsement = Endorsements.create();
        endorsement.setId(UUID.randomUUID().toString());
        endorsement.setPolicyId(policyId);
        endorsement.setEndorsementType(Statuses.ENDORSEMENT_ADD_VEHICLE);
        endorsement.setEndorsementDate(endorsementDate);
        endorsement.setAdditionalPremium(additionalPremium);
        endorsement.setDescription("Added vehicle " + modelYear + " " + make + " " + model);
        persistence.run(Insert.into(Endorsements_.class).entry(endorsement));

        persistence.run(Update.entity(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .data(Map.of(Policies.PREMIUM, policy.getPremium().add(additionalPremium))));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Revised declarations page — policy " + policy.getPolicyNumber(),
                "We added your " + modelYear + " " + make + " " + model + " effective " + endorsementDate
                        + ". The additional premium of $" + additionalPremium.toPlainString()
                        + " is prorated over the remaining term. Your revised declarations page is attached.",
                List.of("declarations-page.pdf")));

        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .columns(p -> p._all(), p -> p.vehicles().expand()))
                .single(Policies.class);
    }
}
