package com.posturestudio.booking.auth;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The brakes on abuse of guide 24, part 6, from application.properties (auth.login-code.*): settings of
 * the server, changed without recompiling. Spring builds the record through its constructor, with the
 * right types (Duration from "PT15M") and immutable values. The HMAC key is not here, on purpose
 * (see LoginCodeHasher).
 */
@ConfigurationProperties("auth.login-code")
record LoginCodeProperties(List<Limit> perDestination, Limit perIp) {

    // Rows are deleted this long after their request (guide 24, 6.5: IP addresses are personal data).
    // No window can be longer, or a limit would count rows already deleted
    static final Duration RETENTION = Duration.ofHours(24);

    // compact constructor: checks run at startup, a wrong configuration stops the application
    LoginCodeProperties {
        perDestination = List.copyOf(Objects.requireNonNull(perDestination, "per-destination limits are required"));
        Objects.requireNonNull(perIp, "the per-ip limit is required");
        Stream.concat(perDestination.stream(), Stream.of(perIp))
                .filter(limit -> limit.window().compareTo(RETENTION) > 0)
                .findAny()
                .ifPresent(limit -> {
                    throw new IllegalArgumentException("A limit window cannot exceed " + RETENTION + ": " + limit);
                });
    }

    // at most "max" codes in any "window"
    record Limit(int max, Duration window) {

        Limit {
            if (max <= 0) {
                throw new IllegalArgumentException("max must be positive: " + max);
            }
            Objects.requireNonNull(window, "window is required");
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("window must be positive: " + window);
            }
        }
    }
}
