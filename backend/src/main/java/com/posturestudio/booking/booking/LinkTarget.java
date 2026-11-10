package com.posturestudio.booking.booking;

import java.time.Instant;

/**
 * A session to be paid in the studio that can receive a payment link (D-059, guide 34, part 3): what the
 * payment module needs to create the Stripe page. Ids and the price copied in the booking (D-011).
 */
public record LinkTarget(long bookingId, long practitionerId, long customerId, long serviceId, long priceCents,
                         Instant startsAt) {
}
