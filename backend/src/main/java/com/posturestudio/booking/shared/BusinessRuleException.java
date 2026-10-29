package com.posturestudio.booking.shared;

import java.util.Objects;

/**
 * The request is well formed and the resource exists, but a business rule forbids the action
 * in the current state (e.g. a package on a deactivated service).
 * Unchecked, like NotFoundException. Becomes a 409 Conflict with the code (D-027).
 */
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode code;

    public BusinessRuleException(ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    public ErrorCode getCode() {
        return code;
    }
}
