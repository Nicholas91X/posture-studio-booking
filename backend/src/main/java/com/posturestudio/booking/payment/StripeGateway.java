package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

import com.posturestudio.booking.booking.CardHolds;

/**
 * What the payment module asks of Stripe, in its own words (guide 31, part 10). The real one is
 * StripeClientGateway; the tests use a fake that remembers the calls and can fail on demand, registered in
 * every test context so that no test ever reaches the real Stripe.
 *
 * Every method takes the ACCOUNT as a mandatory parameter: there is no "default account" in here, so the
 * rule of D-057 (operate on the account of the payment) is in the shape of the code (guide 30, part 8).
 * Every method that creates something takes an idempotency key (guide 31, part 7).
 */
interface StripeGateway {

    // throws ServiceUnavailableException (PAYMENT_PROVIDER_UNAVAILABLE) if Stripe does not answer or refuses
    CheckoutSession createCheckoutSession(PaymentAccount account, NewCheckoutSession session, String idempotencyKey);

    // The state of a Checkout session, read before invalidating a payment link (D-065, guide 34, part 5).
    // Throws ServiceUnavailableException if Stripe does not answer
    CheckoutSessionStatus checkoutSessionStatus(PaymentAccount account, String checkoutSessionId);

    // Makes an OPEN session expire: the customer can no longer pay it. false: it was no longer open (paid, or
    // already expired), the caller reads it again. Throws ServiceUnavailableException if Stripe does not answer
    boolean expireCheckoutSession(PaymentAccount account, String checkoutSessionId, String idempotencyKey);

    enum CheckoutSessionStatus {
        OPEN, COMPLETE, EXPIRED;

        static CheckoutSessionStatus of(String stripeStatus) {
            return switch (stripeStatus == null ? "" : stripeStatus) {
                case "open" -> OPEN;
                case "complete" -> COMPLETE;
                case "expired" -> EXPIRED;
                default -> throw new IllegalStateException("Unknown Checkout session status " + stripeStatus);
            };
        }
    }

    // The state of a PaymentIntent, read before every capture (D-064). Throws ServiceUnavailableException if
    // Stripe does not answer
    PaymentIntentStatus paymentIntentStatus(PaymentAccount account, String paymentIntentId);

    // what a capture needs to know of a PaymentIntent; Stripe's other states all mean "not capturable"
    enum PaymentIntentStatus {
        REQUIRES_CAPTURE, SUCCEEDED, CANCELED, OTHER;

        static PaymentIntentStatus of(String stripeStatus) {
            return switch (stripeStatus == null ? "" : stripeStatus) {
                case "requires_capture" -> REQUIRES_CAPTURE;
                case "succeeded" -> SUCCEEDED;
                case "canceled" -> CANCELED;
                default -> OTHER;
            };
        }
    }

    // Captures a held amount (guide 33). Refused when asking again would not change the answer (the hold expired
    // or was released, the card was refused); throws ServiceUnavailableException if Stripe does not answer
    CardHolds.CaptureResult capturePaymentIntent(PaymentAccount account, String paymentIntentId, String idempotencyKey);

    // Releases a card hold (cancel of the PaymentIntent, guide 32, part 7). true: released. false: no longer
    // cancelable, i.e. already canceled, or CAPTURED (guide 33, part 7): the caller leaves the payment as it is,
    // and payment_intent.canceled or .succeeded will tell which. Throws ServiceUnavailableException if Stripe
    // does not answer
    boolean cancelPaymentIntent(PaymentAccount account, String paymentIntentId, String idempotencyKey);

    // Records nested in an interface are implicitly public, and so must be their compact constructors.
    // The two kinds of Checkout session (guide 37, part 6). sealed with a record each, not an enum: the cases
    // carry different data (a booking id, a package id) and differ in how they pay (held, captured at once).
    // The gateway builds the common part once and switches on the kind, with no default: a third kind does not
    // compile until someone decides what it sends to Stripe
    sealed interface NewCheckoutSession {

        long amountCents();

        String description();

        String customerEmail();

        Instant expiresAt();

        URI successUrl();

        URI cancelUrl();
    }

    // A session for one booking: the amount held, not captured (D-031)
    record BookingCheckout(long bookingId, long amountCents, String description, String customerEmail,
                           Instant expiresAt, URI successUrl, URI cancelUrl) implements NewCheckoutSession {

        public BookingCheckout {
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(successUrl, "successUrl must not be null");
            Objects.requireNonNull(cancelUrl, "cancelUrl must not be null");
        }
    }

    // A session for a package: captured at once (D-031), nothing to approve (guide 37)
    record PackageCheckout(long packageOfferId, long amountCents, String description, String customerEmail,
                           Instant expiresAt, URI successUrl, URI cancelUrl) implements NewCheckoutSession {

        public PackageCheckout {
            Objects.requireNonNull(description, "description must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(successUrl, "successUrl must not be null");
            Objects.requireNonNull(cancelUrl, "cancelUrl must not be null");
        }
    }

    // what Stripe answered: the id (cs_...) and the address of the page
    record CheckoutSession(String id, URI url) {

        public CheckoutSession {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(url, "url must not be null");
        }
    }
}
