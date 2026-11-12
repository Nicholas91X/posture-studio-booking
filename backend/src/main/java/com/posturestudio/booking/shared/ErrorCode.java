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

    BOOKING_ALREADY_STARTED,

    PRACTITIONER_HAS_FUTURE_BOOKINGS,

    BOOKING_NOT_STARTED,

    ATTENDANCE_WINDOW_CLOSED,

    // the practitioner answers after approval_deadline: the request is already void, the job closes it (D-030)
    APPROVAL_DEADLINE_PASSED,

    // booking from the panel for an email that belongs to a practitioner or the admin: one role per user (D-021)
    EMAIL_NOT_A_CUSTOMER,

    // the admin creates a practitioner with an email that already has an account, of any role:
    // one role per user (D-021), nobody is promoted (guide 26, 4.1)
    EMAIL_ALREADY_IN_USE,

    // a gift or an extension to a last valid day already gone: it would give the customer nothing (guide 40)
    CREDIT_VALID_UNTIL_IN_THE_PAST,

    // an extension moves the last valid day forward only (D-069, guide 40, part 4)
    CREDIT_EXTENSION_NOT_LATER,

    // a correction would take a lot below zero (D-067, guide 40, part 5)
    CREDIT_BALANCE_INSUFFICIENT,

    // a customer signed in for the first time and has not given first and last name yet (D-050, guide 25, 7.3)
    PROFILE_INCOMPLETE,

    // the admin assigns a payment account whose name is well formed but not declared in PAYMENT_ACCOUNTS
    // (D-057, guide 30, 9.2)
    PAYMENT_ACCOUNT_UNKNOWN,

    // a customer asks for a slot of a practitioner who has no payment account: nobody could pay it (guide 30, part 6)
    CARD_PAYMENTS_UNAVAILABLE,

    // Stripe did not answer, or refused: nothing was created, the customer tries again in a moment (guide 31, 8.2).
    // A 503, not a 409: the request was right, a service it needs is not available
    PAYMENT_PROVIDER_UNAVAILABLE,

    // the practitioner approves, but the amount held on the card can no longer be captured (hold expired or
    // released, card refused): the request stays waiting, to be rejected or left to expire (guide 33, part 5)
    CARD_CAPTURE_FAILED,

    // a payment link for a session that is not confirmed and to be paid in the studio, or starts too soon for a
    // Stripe page (D-059, guide 34, part 4)
    PAYMENT_LINK_NOT_ALLOWED,

    // the previous link of the session was paid a moment ago: no new link, the payment is on its way (guide 34, part 5)
    PAYMENT_LINK_ALREADY_PAID
}
