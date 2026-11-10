package com.posturestudio.booking.booking;

/**
 * What booking needs of the card, in its own words (D-031): the amount is held on the card at payment,
 * captured at approval, released otherwise. Declared here and implemented by the payment module
 * (StripeCardHolds, D-063), so that the code depends one way only, payment -> booking, as schedule's
 * OccupiedTime (D-044).
 */
public interface CardHolds {

    // At approval: the held amount is charged (guide 33). A refusal that asking again would not change
    // (hold expired or released, card refused) is a VALUE, not an exception: an exception out of a
    // @Transactional bean marks the shared transaction rollback-only even if caught (guide 33, part 4).
    // Throws only when Stripe does not answer (ServiceUnavailableException): then everything rolls back
    CaptureResult capture(long bookingId, long amountCents);

    // At rejection, withdrawal or expiry: the hold is released. Never throws: if Stripe does not answer, the
    // hold dissolves by itself after 7 days, and the rejection must not depend on it (D-031, guide 33, part 7)
    void cancel(long bookingId);

    sealed interface CaptureResult {

        record Captured() implements CaptureResult {
        }

        // why, in Stripe's words (e.g. payment_intent_unexpected_state), for the log
        record Refused(String reason) implements CaptureResult {
        }
    }
}
