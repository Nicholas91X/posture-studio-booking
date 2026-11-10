package com.posturestudio.booking.credit;

// Why a movement was written (V15, D-069). Same values as credit_movement_reason_ck; the amount each one
// allows is in credit_movement_shape_ck. CANCELLATION_RESTORE (+1 for a cancellation in time) arrives with
// step 11
enum MovementReason {

    // +N: the lot is born, whatever its source
    LOT_CREATED,

    // -1: the credit is committed to a booking
    BOOKING,

    // +1: the booking let the credit go, rejected, unanswered or withdrawn (D-030); was APPROVAL_RELEASE in
    // the data model, renamed because a withdrawal is not an approval
    BOOKING_RELEASE,

    // +N or -N: a correction of the practitioner, with who and why (D-067)
    ADJUSTMENT
}
