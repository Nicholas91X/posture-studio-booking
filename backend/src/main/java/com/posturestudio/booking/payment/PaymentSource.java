package com.posturestudio.booking.payment;

// Where a payment comes from (V14, D-065, guide 34, part 3): the webhook decides by it what the payment does
enum PaymentSource {

    // the customer's request of a slot (piece 2): authorized, then approved or not (D-030, D-031)
    REQUEST,

    // a payment link sent by the practitioner for a session to be paid in the studio (D-059): captured at once
    // if the session still waits for it, released otherwise
    LINK
}
