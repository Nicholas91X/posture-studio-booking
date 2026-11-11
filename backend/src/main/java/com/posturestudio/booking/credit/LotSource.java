package com.posturestudio.booking.credit;

// Where a lot comes from (V15, D-069). Same values as credit_lot_source_ck. LATE_PAYMENT does not exist
// (D-068): a payment that arrives late is released. CANCELLATION (a session paid by card, cancelled in time)
// arrives with step 11
enum LotSource {

    // a package bought by the customer, with its payment (credit_lot_payment_ck)
    PACKAGE,

    // a gift of the practitioner, with who and why (D-067)
    GRANT
}
