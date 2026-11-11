package com.posturestudio.booking.payment;

// What a payment pays (V15, D-060, D-069). Same values as payment_purpose_ck
enum PaymentPurpose {

    // a session: a customer's request or a payment link (source)
    BOOKING,

    // a package: credits of a lot, captured at once (D-031); the lot is born at the webhook (step 10, piece 2)
    PACKAGE
}
