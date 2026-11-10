package com.posturestudio.booking.booking;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What booking needs to have a booking paid, in its own words (guide 31, part 3): a page where the customer
 * pays, and whether this practitioner can be paid at all. Declared here and implemented by the payment module
 * (Stripe Checkout), as CardHolds (D-046): booking knows nothing of Stripe, accounts or keys.
 */
public interface CardCheckout {

    // false: the practitioner has no payment account, nobody can pay them by card (guide 30, part 6)
    boolean available(long practitionerId);

    // Called inside the transaction of the request, after the booking row is written (guide 31, 3.3): the
    // page is created where the customer will pay, and recorded. If it fails, it throws, and the whole
    // request is rolled back: no booking without a page
    URI start(CheckoutRequest request);

    // the page of a booking still waiting for its payment, if there is one
    Optional<URI> pageOf(long bookingId);

    // expiresAt: the page must not outlive the hold of the slot (D-008, D-061)
    record CheckoutRequest(long bookingId, long practitionerId, long customerId, long serviceId, long amountCents,
                           Instant expiresAt) {

        public CheckoutRequest {
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }
}
