package com.posturestudio.booking.shared;

import java.time.Duration;
import java.util.Objects;

/**
 * The same thing asked too many times in a period (guide 24, part 6). Becomes a 429 with Retry-After.
 * Not a BusinessRuleException: that is a 409, "the state does not allow it"; here the problem is "too often".
 */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
