package com.posturestudio.booking.payment;

/**
 * A verified Stripe event, reduced to what we use of it (guide 32, part 6). sealed: the webhook's switch
 * lists every kind with no default, and a new kind stops it compiling until someone decides what it does.
 */
sealed interface StripeNotice {

    String eventId();

    // checkout.session.completed: the customer paid; with manual capture the amount is HELD, and
    // payment_status says "unpaid" (guide 32, part 2). status "complete" and a PaymentIntent are what count.
    // For a package, captured at once, payment_status must be "paid" (guide 37, part 7). occurredAt: when Stripe
    // completed the session, from the signed event, the same at every delivery; the validity of a package
    // counts from its day (guide 37, part 8)
    record SessionCompleted(String eventId, String sessionId, String status, String paymentStatus,
                            String paymentIntentId, java.time.Instant occurredAt) implements StripeNotice {
    }

    // checkout.session.expired: the page expired unpaid
    record SessionExpired(String eventId, String sessionId) implements StripeNotice {
    }

    // payment_intent.succeeded: a held amount was captured. Normally after our own commit, so nothing to do;
    // the reconciliation when our commit had failed (guide 33, part 6)
    record PaymentIntentSucceeded(String eventId, String paymentIntentId) implements StripeNotice {
    }

    // payment_intent.canceled: the hold was released, by us or by Stripe after 7 days (guide 33, part 7)
    record PaymentIntentCanceled(String eventId, String paymentIntentId) implements StripeNotice {
    }

    // any other type: answered 200, not recorded
    record Ignored(String eventId, String type) implements StripeNotice {
    }
}
