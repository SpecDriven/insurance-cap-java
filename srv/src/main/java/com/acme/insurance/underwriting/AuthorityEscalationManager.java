package com.acme.insurance.underwriting;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.Statuses;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.AuthorityEscalations;
import cds.gen.insurance.AuthorityEscalations_;
import cds.gen.insurance.AuthorityLimits;
import cds.gen.insurance.AuthorityLimits_;
import cds.gen.insurance.Referrals;
import cds.gen.insurance.Referrals_;

/**
 * Escalates a referral that exceeds the assigned underwriter's authority.
 *
 * Spec: specs/underwriting/referrals/escalate-authority-limit.feature.md
 * Authority limits are CSV seed data in db/data/insurance-AuthorityLimits.csv.
 */
@Component
public class AuthorityEscalationManager {

    private final PersistenceService persistence;
    private final ReferralManager referrals;

    public AuthorityEscalationManager(PersistenceService persistence, ReferralManager referrals) {
        this.persistence = persistence;
        this.referrals = referrals;
    }

    /**
     * "Limit above the underwriter's authority escalates" — a $10,000,000
     * umbrella limit against $5,000,000 of authority escalates to the regional
     * underwriting manager; the underwriter may recommend but not approve.
     *
     * "Discretionary credit above the permitted band" — a 25% credit against a
     * plus-or-minus 15% band escalates, and the competitive justification must
     * be documented before approval.
     *
     * "Recommendation carried forward to the approver" — the recommendation,
     * supporting documents, and rule failures are presented together, and the
     * approver may accept, modify, or reject it.
     */
    public AuthorityEscalations escalate(String referralId, String escalationType,
            BigDecimal requestedAmount, BigDecimal requestedCreditPct, String underwriterRole,
            String recommendation, String recommendedOutcome, String justification) {
        Referrals referral = referrals.load(referralId);
        AuthorityLimits authority = authorityFor(
                underwriterRole == null ? Statuses.ROLE_UNDERWRITER : underwriterRole);

        AuthorityEscalations escalation = AuthorityEscalations.create();
        escalation.setId(UUID.randomUUID().toString());
        escalation.setReferralId(referralId);
        escalation.setEscalationType(escalationType);
        escalation.setRequestedAmount(requestedAmount);
        escalation.setAuthorityLimit(authority.getLimitAmount());
        escalation.setRequestedCreditPct(requestedCreditPct);
        escalation.setPermittedCreditPct(authority.getCreditBandPct());
        escalation.setEscalatedTo(authority.getEscalatesTo());
        escalation.setRecommendation(recommendation);
        escalation.setRecommendedOutcome(recommendedOutcome);
        escalation.setJustification(justification);
        // The underwriter may recommend but not approve their own over-limit request.
        escalation.setApproverMayDecide(true);
        escalation.setStatus(Statuses.ESCALATION_OPEN);

        persistence.run(Insert.into(AuthorityEscalations_.class).entry(escalation));

        referral.setStatus(Statuses.REFERRAL_ESCALATED);
        referral.setEscalatedTo(authority.getEscalatesTo());
        persistence.run(Update.entity(Referrals_.class).entry(referral));

        return load(escalation.getId());
    }

    /** True when the request exceeds the role's granted authority. */
    public boolean exceedsAuthority(String roleName, BigDecimal requestedAmount) {
        AuthorityLimits authority = authorityFor(roleName);
        return requestedAmount != null
                && requestedAmount.compareTo(authority.getLimitAmount()) > 0;
    }

    /** True when the credit is outside the role's permitted plus-or-minus band. */
    public boolean exceedsCreditBand(String roleName, BigDecimal requestedCreditPct) {
        AuthorityLimits authority = authorityFor(roleName);
        return requestedCreditPct != null
                && requestedCreditPct.abs().compareTo(authority.getCreditBandPct()) > 0;
    }

    private AuthorityLimits authorityFor(String roleName) {
        return persistence.run(Select.from(AuthorityLimits_.class)
                .where(a -> a.roleName().eq(roleName)))
                .first(AuthorityLimits.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "No authority limit for role: " + roleName));
    }

    public AuthorityEscalations load(String escalationId) {
        return persistence.run(Select.from(AuthorityEscalations_.class)
                .where(e -> e.ID().eq(escalationId)))
                .first(AuthorityEscalations.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND,
                        "Escalation not found: " + escalationId));
    }
}
