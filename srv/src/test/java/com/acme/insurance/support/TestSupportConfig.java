package com.acme.insurance.support;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system clock with a fixed, test-settable clock so scenarios can
 * travel to due dates, grace-period ends, and renewal windows.
 */
@TestConfiguration
public class TestSupportConfig {

    public static final LocalDate BASE_DATE = LocalDate.of(2026, 1, 15);

    @Bean
    @Primary
    public MutableClock testClock(ZoneId operatingZone) {
        return new MutableClock(operatingZone,
                BASE_DATE.atTime(LocalTime.of(9, 0)).atZone(operatingZone).toInstant());
    }
}
