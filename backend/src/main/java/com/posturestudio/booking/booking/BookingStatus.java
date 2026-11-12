package com.posturestudio.booking.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * The states of a booking (guide 18, part 1.1; step 11: guide 42, part 2): only the ones the code can reach
 * (D-042). Same values as booking_status_ck (V4, widened by V16).
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
    WITHDRAWN,

    // cancelled by the customer within the notice (free_cancellation_until): the credit comes back (D-015, D-076)
    CANCELLED_IN_TIME,

    // cancelled by the customer after the notice: the session is lost
    CANCELLED_LATE,

    // cancelled by the studio, whatever the notice: the customer loses nothing (D-075). Only from CONFIRMED:
    // a pending request cancelled by the studio is a rejection (guide 42, part 4)
    CANCELLED_BY_STUDIO,

    // the session took place: set by a job once a confirmed session has ended (piece 5)
    COMPLETED,

    // the customer did not come: marked by the practitioner, who can correct it back to COMPLETED (piece 5)
    NO_SHOW;

    // The states that hold the time in the database: the same list as the where of booking_no_overlap_ex.
    // No state of step 11 is here: a cancelled session frees the time, a completed one is in the past.
    // For free slots, a PENDING_PAYMENT holds the time only until its hold expires (guide 18, 3.5)
    static final Set<BookingStatus> HOLDS_TIME = EnumSet.of(PENDING_PAYMENT, PENDING_APPROVAL, CONFIRMED);

    // The state machine (guide 20, part 2; guide 42, part 2): the only arrows of the diagram.
    // A switch expression with no default must cover every value: a state added to the enum does not
    // compile until someone decides its arrows (it is how step 11 found them all)
    boolean canMoveTo(BookingStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PENDING_APPROVAL || next == EXPIRED;
            case PENDING_APPROVAL -> next == CONFIRMED || next == REJECTED
                    || next == APPROVAL_EXPIRED || next == WITHDRAWN;
            // the arrows of step 11: cancelled by the customer or the studio, or the session ended.
            // CONFIRMED -> NO_SHOW too: the practitioner may mark it before the job has run (guide 42, part 9)
            case CONFIRMED -> next == CANCELLED_IN_TIME || next == CANCELLED_LATE || next == CANCELLED_BY_STUDIO
                    || next == COMPLETED || next == NO_SHOW;
            // the correction of the practitioner, within a window decided in piece 5 (a Java rule, not an arrow)
            case COMPLETED -> next == NO_SHOW;
            case NO_SHOW -> next == COMPLETED;
            case EXPIRED, REJECTED, APPROVAL_EXPIRED, WITHDRAWN,
                 CANCELLED_IN_TIME, CANCELLED_LATE, CANCELLED_BY_STUDIO -> false;
        };
    }
}
