package com.posturestudio.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A Clock that tests move forward on command (guide 21, part 4): time passes in a test without any
// waiting. It starts where FixedClockConfiguration is stopped, Friday 6 November 2026, 10:00 in Rome.
// withZone() returns a view on the same instant, so StudioTime.today(clock) moves with it
public final class MovableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MovableClock() {
        this(new AtomicReference<>(FixedClockConfiguration.NOW), ZoneOffset.UTC);
    }

    private MovableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    public void reset() {
        now.set(FixedClockConfiguration.NOW);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId otherZone) {
        return new MovableClock(now, otherZone);
    }

    // @Primary: with @SpringBootTest the real Clock exists too, and this one must win
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }
}
