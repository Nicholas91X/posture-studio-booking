package com.posturestudio.booking.booking;

/**
 * What booking needs of the card, in its own words (D-031): the amount is held on the card at payment,
 * captured at approval, released otherwise. Declared here and implemented by the payment module at
 * step 9 (Stripe manual capture), so that the code depends one way only, payment -> booking,
 * as schedule's OccupiedTime (D-044). Until then: LoggingCardHolds.
 */
public interface CardHolds {

    // at approval: the held amount is charged. A failure must leave the booking as it was (guide 20, 3.5)
    void capture(long bookingId, long amountCents);

    // at rejection, withdrawal or expiry: the hold is released. If this fails the hold still dissolves
    // by itself after some days (D-031): at step 9 a failure is logged, it does not stop the rejection
    void cancel(long bookingId);
}
