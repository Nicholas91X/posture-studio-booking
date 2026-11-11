package com.posturestudio.booking.credit;

import java.time.Instant;
import java.util.Objects;

import com.posturestudio.booking.shared.Checks;

/**
 * A package paid, as payment hands it to credit (step 10, guide 37, part 9): who bought what from whom, and
 * the photo taken when the Stripe page was created (D-069): the price paid, the sessions and the validity the
 * customer saw. paidAt is the moment the customer paid, from Stripe's signed event (guide 37, part 8): the
 * validity counts from its day in Rome.
 */
public record PackagePurchase(long paymentId, long customerId, long practitionerId, long serviceId,
                              long packageOfferId, long priceCents, int credits, int validityDays, Instant paidAt) {

    public PackagePurchase {
        Checks.requirePositive(priceCents, "priceCents");
        Checks.requireAtLeast(credits, 2, "credits");
        Checks.requirePositive(validityDays, "validityDays");
        Objects.requireNonNull(paidAt, "paidAt must not be null");
    }
}
