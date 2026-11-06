package com.posturestudio.booking.shared;

/**
 * Readable codes of the business rules, sent to the frontend that translates them into Italian.
 * One place to see them all; new rules add a value here (D-027).
 */
public enum ErrorCode {

    // a package can be sold (created or reactivated) only on an active service
    SERVICE_NOT_ACTIVE,

    // a schedule version that started before today is history: never changed or deleted (D-034)
    SCHEDULE_ALREADY_STARTED,

    // a schedule version cannot be created or moved to a date before today
    SCHEDULE_STARTS_IN_THE_PAST,

    // another version of the same practitioner already starts on that date
    SCHEDULE_DATE_TAKEN
}
