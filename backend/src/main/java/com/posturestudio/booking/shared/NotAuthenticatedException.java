package com.posturestudio.booking.shared;

/**
 * Nobody is acting, or the declared identity matches nobody. Becomes a 401.
 */
public class NotAuthenticatedException extends RuntimeException {

    public NotAuthenticatedException(String message) {
        super(message);
    }
}
