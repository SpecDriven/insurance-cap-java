package com.acme.insurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pro-rata convention (ARCHITECTURE.md): terms are equal monthly units. A
 * partial month counts elapsed days divided by the actual length of that policy
 * month, anchor-to-anchor (actual/actual — e.g. Mar 15→Apr 15 is a 31-day
 * month). A $600 six-month policy cancelled exactly 2 months in earns exactly
 * $200 — matching specs/billing/refund-unearned-premium.feature.md.
 */
public final class ProRata {

    private static final int MONTH_FRACTION_SCALE = 10;

    private ProRata() {
    }

    /** Elapsed policy months from {@code start} to {@code asOf}, anchor-to-anchor. */
    public static BigDecimal monthsElapsed(LocalDate start, LocalDate asOf) {
        if (!asOf.isAfter(start)) {
            return BigDecimal.ZERO;
        }
        long fullMonths = 0;
        LocalDate anchor = start;
        while (!anchor.plusMonths(1).isAfter(asOf)) {
            anchor = anchor.plusMonths(1);
            fullMonths++;
        }
        long daysIntoMonth = ChronoUnit.DAYS.between(anchor, asOf);
        long lengthOfPolicyMonth = ChronoUnit.DAYS.between(anchor, anchor.plusMonths(1));
        return BigDecimal.valueOf(fullMonths).add(
                BigDecimal.valueOf(daysIntoMonth)
                        .divide(BigDecimal.valueOf(lengthOfPolicyMonth), MONTH_FRACTION_SCALE, RoundingMode.HALF_UP));
    }

    /** Earned premium for the elapsed part of the term, rounded HALF_UP to cents. */
    public static BigDecimal earnedPremium(BigDecimal fullTermPremium, int termMonths, LocalDate effectiveDate,
            LocalDate asOf) {
        BigDecimal elapsed = monthsElapsed(effectiveDate, asOf)
                .min(BigDecimal.valueOf(termMonths))
                .max(BigDecimal.ZERO);
        return fullTermPremium.multiply(elapsed)
                .divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
    }

    /** Months remaining in the term as of {@code asOf} (never negative). */
    public static BigDecimal monthsRemaining(int termMonths, LocalDate effectiveDate, LocalDate asOf) {
        return BigDecimal.valueOf(termMonths)
                .subtract(monthsElapsed(effectiveDate, asOf))
                .max(BigDecimal.ZERO);
    }

    /** Prorated share of a full-term amount for the remaining months of the term. */
    public static BigDecimal proratedForRemainingTerm(BigDecimal fullTermAmount, int termMonths,
            LocalDate effectiveDate, LocalDate asOf) {
        return fullTermAmount.multiply(monthsRemaining(termMonths, effectiveDate, asOf))
                .divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
    }
}
