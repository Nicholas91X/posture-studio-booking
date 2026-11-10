package com.posturestudio.booking.payment;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.booking.CardHolds;

/**
 * booking's CardHolds with Stripe (D-063, guide 33): capture at approval, release at rejection, withdrawal and
 * expiry of the answer. Replaces LoggingCardHolds, which confirmed without capturing.
 *
 * Always on the account of the PAYMENT (accounts.of), never the practitioner's current one (D-057). Always with
 * the payment row locked BEFORE calling Stripe (guide 33, part 8): payment_intent.succeeded, born of this very
 * capture, then waits for our commit instead of crossing us.
 *
 * Not @Transactional on purpose: it runs in the caller's transaction (approve, reject, ...), and an exception
 * out of a transactional bean would mark that transaction rollback-only (guide 33, part 4).
 */
@Component
class StripeCardHolds implements CardHolds {

    private static final Logger log = LoggerFactory.getLogger(StripeCardHolds.class);

    private final PaymentRepository payments;
    private final PaymentAccounts accounts;
    private final StripeGateway stripe;
    private final PaymentReleases releases;
    private final Clock clock;

    StripeCardHolds(PaymentRepository payments, PaymentAccounts accounts, StripeGateway stripe,
                    PaymentReleases releases, Clock clock) {
        this.payments = payments;
        this.accounts = accounts;
        this.stripe = stripe;
        this.releases = releases;
        this.clock = clock;
    }

    @Override
    public CaptureResult capture(long bookingId, long amountCents) {
        Payment payment = payments.findLockedByBookingIdAndStatus(bookingId, PaymentStatus.AUTHORIZED)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " has no held payment to capture"));
        if (payment.getAmountCents() != amountCents) {
            throw new IllegalStateException("Booking " + bookingId + " asks " + amountCents + " cents, payment "
                    + payment.getId() + " holds " + payment.getAmountCents());
        }
        // the same key if the practitioner presses "approve" again after an error: Stripe gives back the first
        // capture instead of a second one (guide 33, part 6)
        String paymentIntentId = payment.getStripePaymentIntentId();
        CaptureResult result = stripe.capturePaymentIntent(accounts.of(payment), paymentIntentId, "capture-" + paymentIntentId);
        if (result instanceof CaptureResult.Captured) {
            payment.capture();
        }
        return result;
    }

    @Override
    public void cancel(long bookingId) {
        Payment payment = payments.findLockedByBookingIdAndStatus(bookingId, PaymentStatus.AUTHORIZED).orElse(null);
        if (payment == null) {
            // already released (e.g. by a webhook), or never held: nothing to do
            log.info("Booking {} has no held payment to release", bookingId);
            return;
        }
        // the rejection goes on whatever Stripe answers: a failure is recorded, and the job of D-064 tries again
        releases.attempt(payment, clock.instant());
    }
}
