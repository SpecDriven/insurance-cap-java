package com.acme.insurance.claims;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.Claims;
import cds.gen.insurance.Claims_;

/**
 * Spec: specs/claims/auto-accidents/assign-adjuster.feature.md
 */
@Component
public class AdjusterAssignmentManager {

    // "Assign by estimated damage" severity table
    private static final BigDecimal FAST_TRACK_BELOW = new BigDecimal("5000");
    private static final BigDecimal FIELD_UP_TO = new BigDecimal("25000");

    private final PersistenceService persistence;

    public AdjusterAssignmentManager(PersistenceService persistence) {
        this.persistence = persistence;
    }

    /**
     * "Assign by estimated damage": under $5,000 → fast-track desk adjuster;
     * $5,000 – $25,000 → field adjuster; over $25,000 → senior field adjuster
     * plus supervisor review.
     */
    public Claims recordDamageEstimate(String claimId, BigDecimal estimatedDamage) {
        persistence.run(Select.from(Claims_.class).where(c -> c.ID().eq(claimId)))
                .first(Claims.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Claim not found"));
        if (estimatedDamage == null || estimatedDamage.signum() < 0) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "A damage estimate is required");
        }

        String route;
        boolean supervisorReview = false;
        if (estimatedDamage.compareTo(FAST_TRACK_BELOW) < 0) {
            route = Statuses.ROUTE_FAST_TRACK_DESK;
        } else if (estimatedDamage.compareTo(FIELD_UP_TO) <= 0) {
            route = Statuses.ROUTE_FIELD;
        } else {
            route = Statuses.ROUTE_SENIOR_FIELD;
            supervisorReview = true;
        }

        Map<String, Object> data = new HashMap<>();
        data.put(Claims.ESTIMATED_DAMAGE, estimatedDamage);
        data.put(Claims.ADJUSTER_ROUTE, route);
        data.put(Claims.SUPERVISOR_REVIEW_REQUIRED, supervisorReview);
        data.put(Claims.STATUS, Statuses.CLAIM_OPEN_ASSIGNED);
        persistence.run(Update.entity(Claims_.class).where(c -> c.ID().eq(claimId)).data(data));

        return persistence.run(Select.from(Claims_.class).where(c -> c.ID().eq(claimId)))
                .single(Claims.class);
    }
}
