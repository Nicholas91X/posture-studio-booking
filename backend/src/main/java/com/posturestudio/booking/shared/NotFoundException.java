package com.posturestudio.booking.shared;

/**
 * Something does not exist, or does not belong to the caller (it must look the same: D-026).
 * Unchecked, so that @Transactional rolls back and methods need no "throws".
 * Becomes a 404 in the REST error handler.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
