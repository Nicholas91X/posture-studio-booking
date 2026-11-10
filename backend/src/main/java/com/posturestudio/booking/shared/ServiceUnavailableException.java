package com.posturestudio.booking.shared;

import java.util.Objects;

/**
 * The request was right, but an external service it needs did not answer or refused (e.g. Stripe, guide 31,
 * 8.2). Becomes a 503 with a readable code: the client may try again in a moment. Unchecked, so that the
 * transaction of the request rolls back and nothing is left half done.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final ErrorCode code;

    public ServiceUnavailableException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ErrorCode getCode() {
        return code;
    }
}
