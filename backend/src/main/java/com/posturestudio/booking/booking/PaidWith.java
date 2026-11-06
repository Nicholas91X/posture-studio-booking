package com.posturestudio.booking.booking;

/**
 * How a booking is paid. Same values as booking_paid_with_ck; CREDIT arrives with step 10.
 */
enum PaidWith {

    // with a card through Stripe: held at request, captured at approval (D-031)
    CARD,

    // in the studio: only for bookings made from the panel, no credit if cancelled in time (D-040)
    IN_STUDIO
}
