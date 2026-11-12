package com.posturestudio.booking.credit;

/**
 * Who cancelled the session whose credit comes back (D-076, guide 43, part 4.2): it decides whether a credit that
 * was already extended once can be extended again. A loop of "book, cancel in time" needs the CUSTOMER to repeat
 * it; a cancellation of the STUDIO is not in the customer's hands, so it always guarantees the minimum validity.
 */
public enum CancelledBy {
    CUSTOMER,
    STUDIO
}
