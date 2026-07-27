package com.acme.insurance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Money convention (ARCHITECTURE.md): installment plans allocate remainder
 * cents largest-first so the installments sum exactly to the premium plus fees
 * (e.g. $517 over 6 payments → 86.17, 86.17, 86.17, 86.17, 86.16, 86.16).
 */
public final class InstallmentAllocator {

    private InstallmentAllocator() {
    }

    /** Split {@code total} into {@code n} shares; earlier shares carry the extra cents. */
    public static List<BigDecimal> allocate(BigDecimal total, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        long cents = total.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long base = cents / n;
        long remainder = cents % n;
        List<BigDecimal> shares = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long share = base + (i < remainder ? 1 : 0);
            shares.add(BigDecimal.valueOf(share).movePointLeft(2).setScale(2));
        }
        return shares;
    }
}
