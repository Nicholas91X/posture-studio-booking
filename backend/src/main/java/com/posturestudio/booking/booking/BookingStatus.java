package com.posturestudio.booking.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * The states of a booking in step 7 (guide 18, part 1.1): only the ones the code can reach (D-042).
 * Same values as booking_status_ck; steps 10 and 11 add the cancellations with a new migration.
 * Public: the API returns it, so its names are part of the contract and are never renamed (as D-027).
 */
public enum BookingStatus {

    // the customer is paying: the time is held until hold_expires_at (D-008)
    PENDING_PAYMENT,

    // amount held on the card, waiting for the practitioner until approval_deadline (D-030, D-031)
    PENDING_APPROVAL,

    // approved and paid
    CONFIRMED,

    // payment not completed in time
    EXPIRED,

    // rejected by the practitioner: the hold on the card is cancelled
    REJECTED,

    // the practitioner did not answer in time: like a rejection
    APPROVAL_EXPIRED,

    // withdrawn by the customer while waiting for approval: nothing charged
    WITHDRAWN;

    // The states that hold the time in the database: the same list as the where of booking_no_overlap_ex.
    // For free slots, a PENDING_PAYMENT holds the time only until its hold expires (guide 18, 3.5)
    static final Set<BookingStatus> HOLDS_TIME = EnumSet.of(PENDING_PAYMENT, PENDING_APPROVAL, CONFIRMED);

    // The state machine (guide 20, part 2): the only arrows of the diagram of guide 18, 1.1.
    // A switch expression with no default must cover every value: a state added at step 11 will not
    // compile until someone decides its arrows
    boolean canMoveTo(BookingStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PENDING_APPROVAL || next == EXPIRED;
            case PENDING_APPROVAL -> next == CONFIRMED || next == REJECTED
                    || next == APPROVAL_EXPIRED || next == WITHDRAWN;
            // final in step 7: cancelling a confirmed booking arrives with step 11
            case CONFIRMED, EXPIRED, REJECTED, APPROVAL_EXPIRED, WITHDRAWN -> false;
        };
    }
}
