package com.posturestudio.booking.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PROVISIONAL (guide 20, part 3). TODO (step 9): remove this class; the payment module implements
 * CardHolds with Stripe (capture or cancel of the PaymentIntent). A "done" criterion of step 9.
 *
 * NO LONGER HARMLESS since step 9, piece 3: the Stripe webhook brings real bookings to PENDING_APPROVAL,
 * and an approval here CONFIRMS A BOOKING WITHOUT CAPTURING the money (the hold dissolves after ~7 days).
 * Test mode only: piece 4 replaces this class, and pieces 3 and 4 reach main together (guide 32, part 10).
 */
@Component
class LoggingCardHolds implements CardHolds {

    private static final Logger log = LoggerFactory.getLogger(LoggingCardHolds.class);

    @Override
    public void capture(long bookingId, long amountCents) {
        log.info("PROVISIONAL: would capture {} cents for booking {}", amountCents, bookingId);
    }

    @Override
    public void cancel(long bookingId) {
        log.info("PROVISIONAL: would cancel the card hold of booking {}", bookingId);
    }
}
