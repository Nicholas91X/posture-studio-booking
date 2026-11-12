package com.posturestudio.booking.practitioner;

import java.time.LocalDate;

/**
 * A lot still valid of a customer of a deactivated practitioner (D-077, guide 45, part 6): whose, so that the admin
 * can contact the customer, for what, how many credits, until when.
 */
public record OutstandingCredit(long lotId, long customerId, String firstName, String lastName, String email,
                                long serviceId, long balance, LocalDate validUntil) {
}
