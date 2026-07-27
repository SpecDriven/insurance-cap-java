package com.acme.insurance.adapters;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.acme.insurance.ports.PaymentGatewayPort;
import com.acme.insurance.ports.PaymentResult;

/**
 * In-memory payment gateway stub. Approves everything by default; tests can
 * script declines per payment method token and assert on recorded calls.
 */
@Component
public class InMemoryPaymentGateway implements PaymentGatewayPort {

    public record Charge(String paymentMethodToken, BigDecimal amount, boolean approved) {}
    public record Refund(String paymentMethodToken, BigDecimal amount) {}
    public record Payout(String payeeName, BigDecimal amount) {}

    private final List<Charge> charges = new CopyOnWriteArrayList<>();
    private final List<Refund> refunds = new CopyOnWriteArrayList<>();
    private final List<Payout> payouts = new CopyOnWriteArrayList<>();
    private final Set<String> decliningTokens = ConcurrentHashMap.newKeySet();

    @Override
    public PaymentResult charge(String paymentMethodToken, BigDecimal amount) {
        boolean approved = !decliningTokens.contains(paymentMethodToken);
        charges.add(new Charge(paymentMethodToken, amount, approved));
        return approved ? PaymentResult.success() : PaymentResult.decline("card_declined");
    }

    @Override
    public void refund(String paymentMethodToken, BigDecimal amount) {
        refunds.add(new Refund(paymentMethodToken, amount));
    }

    @Override
    public void payOut(String payeeName, BigDecimal amount) {
        payouts.add(new Payout(payeeName, amount));
    }

    /** Script all further charges against this token to be declined. */
    public void declineChargesFor(String paymentMethodToken) {
        decliningTokens.add(paymentMethodToken);
    }

    /** Charges against this token succeed again. */
    public void approveChargesFor(String paymentMethodToken) {
        decliningTokens.remove(paymentMethodToken);
    }

    public List<Charge> charges() {
        return List.copyOf(charges);
    }

    public List<Refund> refunds() {
        return List.copyOf(refunds);
    }

    public List<Payout> payouts() {
        return List.copyOf(payouts);
    }

    public void reset() {
        charges.clear();
        refunds.clear();
        payouts.clear();
        decliningTokens.clear();
    }
}
