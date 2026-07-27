package com.acme.insurance.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Fixed, test-settable clock (ARCHITECTURE.md "Scheduling": job logic lives in
 * plain services that tests invoke directly with a fixed Clock).
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(ZoneId zone, Instant initial) {
        this.zone = zone;
        this.instant = initial;
    }

    /** Sets the clock to 09:00 local time on the given business date. */
    public void setBusinessDate(LocalDate date) {
        this.instant = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
    }

    public LocalDate businessDate() {
        return LocalDate.now(this);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(zone, instant);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
