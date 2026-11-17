package com.posturestudio.booking.customer;

/**
 * The customer of an email typed in the panel (D-048): its id, and whether it was created by this very call.
 * created is true when nobody with this email was a customer of the studio yet: the panel asks the
 * practitioner to check the address, because a typo makes a new customer, not an error (D-088).
 */
public record FoundCustomer(long id, boolean created) {
}
