package com.posturestudio.booking.booking;

/**
 * Who made the booking (D-024, D-042). The person follows from the booking itself:
 * CUSTOMER is its customer_id, PRACTITIONER is its practitioner_id, the only one who can book
 * for a customer from the panel. Same values as booking_booked_by_ck.
 * Public: the panel lists show it, so its names are part of the API contract (as D-027).
 */
public enum BookedBy {

    CUSTOMER,

    PRACTITIONER
}
