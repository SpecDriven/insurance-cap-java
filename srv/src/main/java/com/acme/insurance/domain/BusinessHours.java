package com.acme.insurance.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Business-hour arithmetic for underwriting referral SLAs.
 *
 * Spec: specs/underwriting/referrals/apply-referral-sla.feature.md
 * "Weekend and holiday hours are not counted" — a referral queued at 4:00 PM on
 * the Friday before a Monday holiday counts only business hours from Tuesday
 * morning onward.
 *
 * The business day runs 9:00 AM to 5:00 PM (8 business hours) in the company's
 * operating timezone; all arithmetic here is on local date-times supplied by the
 * caller's Clock (ARCHITECTURE.md "Timezone policy").
 */
public final class BusinessHours {

    public static final LocalTime DAY_START = LocalTime.of(9, 0);
    public static final LocalTime DAY_END = LocalTime.of(17, 0);
    public static final int HOURS_PER_DAY = 8;

    private BusinessHours() {
    }

    /**
     * Fixed-date US holidays observed by the company, plus the floating holidays
     * the referral SLA scenarios rely on. Kept deliberately small: only the
     * holidays needed to evaluate the published scenarios.
     */
    public static boolean isHoliday(LocalDate date) {
        Set<LocalDate> holidays = Set.of(
                LocalDate.of(date.getYear(), Month.JANUARY, 1),
                LocalDate.of(date.getYear(), Month.JULY, 4),
                LocalDate.of(date.getYear(), Month.DECEMBER, 25),
                nthWeekdayOf(date.getYear(), Month.JANUARY, DayOfWeek.MONDAY, 3),   // MLK Day
                nthWeekdayOf(date.getYear(), Month.FEBRUARY, DayOfWeek.MONDAY, 3),  // Presidents Day
                lastWeekdayOf(date.getYear(), Month.MAY, DayOfWeek.MONDAY),         // Memorial Day
                nthWeekdayOf(date.getYear(), Month.SEPTEMBER, DayOfWeek.MONDAY, 1), // Labor Day
                nthWeekdayOf(date.getYear(), Month.NOVEMBER, DayOfWeek.THURSDAY, 4) // Thanksgiving
        );
        return holidays.contains(date);
    }

    public static boolean isBusinessDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !isHoliday(date);
    }

    /** The next instant at which business hours are running, at or after {@code from}. */
    public static LocalDateTime nextBusinessInstant(LocalDateTime from) {
        LocalDateTime cursor = from;
        while (true) {
            LocalDate day = cursor.toLocalDate();
            if (!isBusinessDay(day)) {
                cursor = day.plusDays(1).atTime(DAY_START);
                continue;
            }
            if (cursor.toLocalTime().isBefore(DAY_START)) {
                return day.atTime(DAY_START);
            }
            if (!cursor.toLocalTime().isBefore(DAY_END)) {
                cursor = day.plusDays(1).atTime(DAY_START);
                continue;
            }
            return cursor;
        }
    }

    /** Business hours elapsed between two instants, excluding nights, weekends, and holidays. */
    public static double between(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            return 0d;
        }
        LocalDateTime cursor = nextBusinessInstant(start);
        double minutes = 0d;
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.toLocalDate();
            LocalDateTime dayEnd = day.atTime(DAY_END);
            LocalDateTime segmentEnd = end.isBefore(dayEnd) ? end : dayEnd;
            if (segmentEnd.isAfter(cursor)) {
                minutes += ChronoUnit.MINUTES.between(cursor, segmentEnd);
            }
            cursor = nextBusinessInstant(day.plusDays(1).atTime(DAY_START));
        }
        return minutes / 60d;
    }

    /** The instant {@code hours} business hours after {@code start}. */
    public static LocalDateTime plusBusinessHours(LocalDateTime start, double hours) {
        LocalDateTime cursor = nextBusinessInstant(start);
        double remaining = hours;
        while (remaining > 0) {
            LocalDate day = cursor.toLocalDate();
            LocalDateTime dayEnd = day.atTime(DAY_END);
            double availableToday = ChronoUnit.MINUTES.between(cursor, dayEnd) / 60d;
            if (remaining <= availableToday) {
                return cursor.plusMinutes(Math.round(remaining * 60));
            }
            remaining -= availableToday;
            cursor = nextBusinessInstant(day.plusDays(1).atTime(DAY_START));
        }
        return cursor;
    }

    /** {@code count}th {@code weekday} of the month, 1-based. */
    private static LocalDate nthWeekdayOf(int year, Month month, DayOfWeek weekday, int count) {
        LocalDate first = LocalDate.of(year, month, 1);
        int shift = (weekday.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        return first.plusDays(shift + 7L * (count - 1));
    }

    private static LocalDate lastWeekdayOf(int year, Month month, DayOfWeek weekday) {
        LocalDate last = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        int shift = (last.getDayOfWeek().getValue() - weekday.getValue() + 7) % 7;
        return last.minusDays(shift);
    }
}
