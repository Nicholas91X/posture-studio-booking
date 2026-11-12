package com.posturestudio.booking.credit;

// Where a lot comes from (V15, D-069; V16). Same values as credit_lot_source_ck. LATE_PAYMENT does not exist
// (D-068): a payment that arrives late is released
enum LotSource {

    // a package bought by the customer, with its payment (credit_lot_payment_ck)
    PACKAGE,

    // a gift of the practitioner, with who and why (D-067)
    GRANT,

    // a credit of 1 born from a cancellation (in time, or by the studio): a session paid by card, or a credit
    // whose lot ended too soon (minimum validity, D-076). Its LOT_CREATED movement carries the booking (V16).
    // Always "already extended": a cancellation of the customer never extends it again (D-078, guide 42, part 8)
    CANCELLATION
}
