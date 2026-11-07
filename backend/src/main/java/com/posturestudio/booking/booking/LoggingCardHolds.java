package com.posturestudio.booking.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PROVISIONAL (guide 20, part 3). TODO (step 9): remove this class; the payment module implements
 * CardHolds with Stripe (capture or cancel of the PaymentIntent). A "done" criterion of step 9.
 *
 * Harmless on its own: without the simulated payment (booking.simulated-payments, off by default)
 * no booking ever reaches PENDING_APPROVAL, so nothing is ever "captured" here.
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
