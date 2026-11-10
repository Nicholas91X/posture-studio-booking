package com.posturestudio.booking.payment;

/**
 * A verified Stripe event, reduced to what we use of it (guide 32, part 6). sealed: the webhook's switch
 * lists every kind with no default, and a new kind stops it compiling until someone decides what it does.
 */
sealed interface StripeNotice {

    String eventId();

    // checkout.session.completed: the customer paid; with manual capture the amount is HELD, and
    // payment_status says "unpaid" (guide 32, part 2). status "complete" and a PaymentIntent are what count
    record SessionCompleted(String eventId, String sessionId, String status, String paymentIntentId)
            implements StripeNotice {
    }

    // checkout.session.expired: the page expired unpaid
    record SessionExpired(String eventId, String sessionId) implements StripeNotice {
    }

    // any other type: answered 200, not recorded
    record Ignored(String eventId, String type) implements StripeNotice {
    }
}
