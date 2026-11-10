package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

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

    // Records nested in an interface are implicitly public, and so must be their compact constructors.
    // A Checkout session for one booking, amount held and not captured (D-031)
    record NewCheckoutSession(long bookingId, long amountCents, String description, String customerEmail,
                              Instant expiresAt, URI successUrl, URI cancelUrl) {

        public NewCheckoutSession {
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
