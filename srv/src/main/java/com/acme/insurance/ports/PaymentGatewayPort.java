package com.acme.insurance.ports;

import java.math.BigDecimal;

/**
 * Payment gateway port (see ARCHITECTURE.md "Outbound integrations"):
 * autopay charges/declines, refunds to the original payment method, and
 * direct payments to repair shops.
 */
public interface PaymentGatewayPort {

    /** Charge the insured's stored payment method (autopay, down payment). */
    PaymentResult charge(String paymentMethodToken, BigDecimal amount);

    /** Refund to the original payment method. */
    void refund(String paymentMethodToken, BigDecimal amount);

    /** Pay a third party directly (e.g. a repair shop). */
    void payOut(String payeeName, BigDecimal amount);
}
