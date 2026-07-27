package com.acme.insurance.policy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.acme.insurance.domain.ProRata;
import com.acme.insurance.domain.Statuses;
import com.acme.insurance.ports.PaymentGatewayPort;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.insurance.CancellationNotices;
import cds.gen.insurance.CancellationNotices_;
import cds.gen.insurance.Installments;
import cds.gen.insurance.Policies;
import cds.gen.insurance.Policies_;
import cds.gen.insurance.Refunds;
import cds.gen.insurance.Refunds_;

/**
 * Spec: specs/policy/auto-policy/cancel-policy.feature.md
 * Spec: specs/billing/refund-unearned-premium.feature.md
 */
@Component
public class CancellationManager {

    // "Cancellation for non-payment": the notice gives 14 days to pay
    private static final int NOTICE_PERIOD_DAYS = 14;

    public static final String REASON_INSURED_REQUEST = "insured request";
    public static final String REASON_NON_PAYMENT = "non-payment";

    private final PersistenceService persistence;
    private final PaymentGatewayPort paymentGateway;
    private final Clock clock;

    public CancellationManager(PersistenceService persistence, PaymentGatewayPort paymentGateway,
            Clock clock) {
        this.persistence = persistence;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    /**
     * "Insured requests cancellation": the policy is cancelled on the chosen
     * date and unearned premium is refunded pro rata.
     */
    public Policies requestCancellation(String policyId, LocalDate cancellationDate) {
        return cancel(policyId, cancellationDate, REASON_INSURED_REQUEST);
    }

    /**
     * Cancels the policy and settles the final account: earned premium follows
     * the pro-rata convention (equal monthly units, anchor-to-anchor); whatever
     * the insured paid beyond the earned premium is refunded to the original
     * payment method ("Pro-rata refund on cancellation" — a $600 six-month
     * policy cancelled exactly 2 months in refunds $400).
     */
    public Policies cancel(String policyId, LocalDate cancellationDate, String reason) {
        Policies policy = loadWithInstallments(policyId);
        if (!Statuses.POLICY_IN_FORCE.equals(policy.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The policy is not in force");
        }
        if (cancellationDate == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST, "A cancellation date is required");
        }
        if (cancellationDate.isBefore(policy.getEffectiveDate())) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                    "The cancellation date precedes the policy effective date");
        }

        BigDecimal earned = ProRata.earnedPremium(policy.getPremium(), policy.getTermMonths(),
                policy.getEffectiveDate(), cancellationDate);
        BigDecimal paid = policy.getInstallments().stream()
                .filter(i -> Statuses.INSTALLMENT_PAID.equals(i.getStatus()))
                .map(Installments::getPremiumAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refund = paid.subtract(earned).max(BigDecimal.ZERO).setScale(2);

        Map<String, Object> data = new HashMap<>();
        data.put(Policies.STATUS, Statuses.POLICY_CANCELLED);
        data.put(Policies.CANCELLATION_DATE, cancellationDate);
        data.put(Policies.CANCELLATION_REASON, reason);
        persistence.run(Update.entity(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .data(data));

        if (refund.signum() > 0) {
            paymentGateway.refund(policy.getPaymentMethodToken(), refund);
            Refunds refundRow = Refunds.create();
            refundRow.setId(UUID.randomUUID().toString());
            refundRow.setPolicyId(policyId);
            refundRow.setAmount(refund);
            refundRow.setRefundedOn(LocalDate.now(clock));
            refundRow.setMethod(Statuses.REFUND_METHOD_ORIGINAL);
            persistence.run(Insert.into(Refunds_.class).entry(refundRow));
        }

        return loadWithInstallments(policyId);
    }

    /**
     * Explicit issue action for the notice of cancellation for non-payment.
     * Precondition: an installment is past due and its 10-day grace period has
     * ended. The notice states a cancellation date 14 days out. (The nightly
     * automation that would issue notices is [proposed] and stays absent.)
     */
    public CancellationNotices issueNotice(String policyId) {
        LocalDate today = LocalDate.now(clock);
        Policies policy = loadWithInstallments(policyId);
        if (!Statuses.POLICY_IN_FORCE.equals(policy.getStatus())) {
            throw new ServiceException(ErrorStatuses.CONFLICT, "The policy is not in force");
        }
        Installments pastDue = policy.getInstallments().stream()
                .filter(i -> Statuses.INSTALLMENT_PAST_DUE.equals(i.getStatus()))
                .filter(i -> i.getGraceEndsOn() != null && !i.getGraceEndsOn().isAfter(today))
                .findFirst()
                .orElseThrow(() -> new ServiceException(ErrorStatuses.CONFLICT,
                        "No past-due installment beyond its grace period"));

        CancellationNotices notice = CancellationNotices.create();
        notice.setId(UUID.randomUUID().toString());
        notice.setPolicyId(policyId);
        notice.setIssuedOn(today);
        notice.setCancellationDate(today.plusDays(NOTICE_PERIOD_DAYS));
        notice.setPastDueAmount(pastDue.getTotalAmount());
        notice.setStatus(Statuses.NOTICE_ISSUED);
        persistence.run(Insert.into(CancellationNotices_.class).entry(notice));
        return notice;
    }

    /**
     * "Cancellation for non-payment": once the 14-day notice period elapses
     * without payment, the policy is cancelled effective the date stated on the
     * notice and any unearned premium is refunded. If the past-due amount was
     * paid in the meantime, nothing is cancelled (formal rescission of the
     * notice is [proposed] and stays absent).
     */
    public void processDueCancellations(LocalDate today) {
        List<CancellationNotices> dueNotices = persistence.run(Select.from(CancellationNotices_.class)
                .where(n -> n.status().eq(Statuses.NOTICE_ISSUED)
                        .and(n.cancellationDate().le(today))))
                .listOf(CancellationNotices.class);

        for (CancellationNotices notice : dueNotices) {
            Policies policy = loadWithInstallments(notice.getPolicyId());
            boolean stillUnpaid = policy.getInstallments().stream()
                    .anyMatch(i -> Statuses.INSTALLMENT_PAST_DUE.equals(i.getStatus()));
            if (stillUnpaid && Statuses.POLICY_IN_FORCE.equals(policy.getStatus())) {
                cancel(notice.getPolicyId(), notice.getCancellationDate(), REASON_NON_PAYMENT);
                persistence.run(Update.entity(CancellationNotices_.class)
                        .where(n -> n.ID().eq(notice.getId()))
                        .data(Map.of(CancellationNotices.STATUS, Statuses.NOTICE_EXECUTED)));
            }
        }
    }

    private Policies loadWithInstallments(String policyId) {
        return persistence.run(Select.from(Policies_.class)
                .where(p -> p.ID().eq(policyId))
                .columns(p -> p._all(), p -> p.installments().expand()))
                .first(Policies.class)
                .orElseThrow(() -> new ServiceException(ErrorStatuses.NOT_FOUND, "Policy not found"));
    }
}
