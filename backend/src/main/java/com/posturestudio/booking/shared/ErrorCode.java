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
    SCHEDULE_DATE_TAKEN,

    // the requested start is not among the free slots: off the grid, outside working hours, too soon,
    // beyond the horizon, during an absence, or taken. One code on purpose: the customer picks another
    // slot whatever the reason, and telling "taken" from "not working" would reveal bookings (guide 19, 2.2)
    SLOT_NOT_AVAILABLE,

    // the customer already has max_future_bookings future bookings with this practitioner (D-023, D-041)
    BOOKING_LIMIT_REACHED,

    // the booking is not in a state that allows this step, e.g. approving a request just withdrawn (guide 20, part 2)
    BOOKING_TRANSITION_NOT_ALLOWED,

    // the practitioner answers after approval_deadline: the request is already void, the job closes it (D-030)
    APPROVAL_DEADLINE_PASSED,

    // booking from the panel for an email that belongs to a practitioner or the admin: one role per user (D-021)
    EMAIL_NOT_A_CUSTOMER,

    // the admin creates a practitioner with an email that already has an account, of any role:
    // one role per user (D-021), nobody is promoted (guide 26, 4.1)
    EMAIL_ALREADY_IN_USE,

    // a customer signed in for the first time and has not given first and last name yet (D-050, guide 25, 7.3)
    PROFILE_INCOMPLETE
}
