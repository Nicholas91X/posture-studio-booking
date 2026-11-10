package com.posturestudio.booking.booking;

/**
 * What a booking answers when a card hold reaches it (guide 32, part 7). It answers instead of acting: only
 * the caller (the Stripe webhook) knows exactly WHICH payment arrived, so only the caller can release it.
 *
 * sealed: these two records are the only answers, so the caller's switch lists both with no default, and a
 * third answer would stop it compiling until handled (pattern matching for switch, Java 21).
 */
public sealed interface PaymentOutcome {

    // the booking took the payment: PENDING_APPROVAL, or CONFIRMED in automatic mode
    record Accepted(BookingStatus status) implements PaymentOutcome {
    }

    // the booking does not want this payment (expired, already paid, rejected, ...): release this hold.
    // Never an error: for Stripe an error means "try again" for three days (guide 32, part 7)
    record NotWanted(BookingStatus status) implements PaymentOutcome {
    }
}
