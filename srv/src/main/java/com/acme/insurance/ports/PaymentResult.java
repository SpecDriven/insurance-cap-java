package com.acme.insurance.ports;

public record PaymentResult(boolean approved, String declineCode) {

    public static PaymentResult success() {
        return new PaymentResult(true, null);
    }

    public static PaymentResult decline(String code) {
        return new PaymentResult(false, code);
    }
}
