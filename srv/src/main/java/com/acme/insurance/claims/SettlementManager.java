package com.acme.insurance.claims;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.EmailMessage;
import com.acme.insurance.ports.EmailPort;
import com.acme.insurance.ports.PaymentGatewayPort;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;

/**
 * Spec: specs/claims/auto-accidents/settle-collision-claim.feature.md
 */
@Component
public class SettlementManager {

    private final PersistenceService persistence;
    private final PaymentGatewayPort paymentGateway;
    private final EmailPort email;

    public SettlementManager(PersistenceService persistence, PaymentGatewayPort paymentGateway,
            EmailPort email) {
        this.persistence = persistence;
        this.paymentGateway = paymentGateway;
        this.email = email;
    }

    /** Records the approved repair-shop estimate on the claim. */
    public Claims recordRepairEstimate(String claimId, String repairShopName, BigDecimal amount) {
        loadClaim(claimId);
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "A positive estimate amount is required");
        }
        Map<String, Object> data = new HashMap<>();
        data.put(Claims.REPAIR_SHOP_NAME, repairShopName);
        data.put(Claims.REPAIR_ESTIMATE_AMOUNT, amount);
        persistence.run(Update.entity(Claims_.class).where(c -> c.ID().eq(claimId)).data(data));
        return loadClaim(claimId);
    }

    /**
     * "Approve repair estimate": the estimate minus the deductible is paid
     * directly to the repair shop, and the insured is notified that the
     * deductible is due to the shop.
     */
    public Claims authorizeRepair(String claimId) {
        Claims claim = loadClaim(claimId);
        if (claim.getRepairEstimateAmount() == null || claim.getRepairShopName() == null) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "No approved repair estimate on file");
        }
        if (claim.getDeductible() == null) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The claim has no deductible on file");
        }

        Policies policy = persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(claim.getPolicyId())))
                .single(Policies.class);

        BigDecimal payToShop = claim.getRepairEstimateAmount()
                .subtract(claim.getDeductible())
                .max(BigDecimal.ZERO)
                .setScale(2);

        paymentGateway.payOut(claim.getRepairShopName(), payToShop);

        Map<String, Object> data = new HashMap<>();
        data.put(Claims.PAID_TO_SHOP_AMOUNT, payToShop);
        data.put(Claims.STATUS, Statuses.CLAIM_REPAIR_AUTHORIZED);
        persistence.run(Update.entity(Claims_.class).where(c -> c.ID().eq(claimId)).data(data));

        email.send(new EmailMessage(policy.getNamedInsuredEmail(),
                "Repair authorized — claim " + claim.getClaimNumber(),
                "We authorized the repair at " + claim.getRepairShopName() + " and paid $"
                        + payToShop.toPlainString() + " directly to the shop. Your $"
                        + claim.getDeductible().toPlainString() + " deductible is due to the shop."));

        return loadClaim(claimId);
    }

    private Claims loadClaim(String claimId) {
        return persistence.run(Select.from(Claims_.class).where(c -> c.ID().eq(claimId)))
                .first(Claims.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Claim not found"));
    }
}
