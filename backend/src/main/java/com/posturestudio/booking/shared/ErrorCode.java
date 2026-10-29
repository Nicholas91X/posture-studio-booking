package com.posturestudio.booking.shared;

/**
 * Readable codes of the business rules, sent to the frontend that translates them into Italian.
 * One place to see them all; new rules add a value here (D-027).
 */
public enum ErrorCode {

    // a package can be sold (created or reactivated) only on an active service
    SERVICE_NOT_ACTIVE
}
