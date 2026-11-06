package com.posturestudio.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A Clock stopped on Friday 6 November 2026, 10:00 in Rome (09:00 UTC): rules about "today" and
// "now" are tested on a date we chose, not on the day the test runs.
// @Primary: with @SpringBootTest the real Clock exists too, and this one must win
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfiguration {

    public static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
