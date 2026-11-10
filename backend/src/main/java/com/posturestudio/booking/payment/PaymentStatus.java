package com.posturestudio.booking.payment;

import java.util.Set;

// The states of a payment (V10, guide 30, 7.2). The transitions arrive with pieces 2-5
enum PaymentStatus {

    // Checkout session created, the customer has not paid yet
    PENDING,
    // amount held on the card (D-031)
    AUTHORIZED,
    // captured
    SUCCEEDED,
    // hold released: rejection, expiry of the answer, withdrawal, late payment, a link no longer wanted (D-059)
    CANCELED,
    // the Checkout session expired unpaid
    EXPIRED;

    // still to be captured or released, on the account it was made on: that account must stay configured
    // (guide 30, 4.3)
    static final Set<PaymentStatus> OPEN = Set.of(PENDING, AUTHORIZED);
}
