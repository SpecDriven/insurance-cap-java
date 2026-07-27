package com.acme.insurance.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Timezone policy (ARCHITECTURE.md): every business date is computed in the
 * company's operating timezone, configured by insurance.time-zone and injected
 * as java.time.Clock. Services never call now() without the Clock.
 */
@Configuration
public class TimeConfig {

    @Bean
    public ZoneId operatingZone(@Value("${insurance.time-zone:America/New_York}") String timeZone) {
        return ZoneId.of(timeZone);
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock(ZoneId operatingZone) {
        return Clock.system(operatingZone);
    }
}
