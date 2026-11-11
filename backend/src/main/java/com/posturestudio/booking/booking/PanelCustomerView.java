package com.posturestudio.booking.booking;

/**
 * A customer of the practitioner, in "my customers" (D-066, guide 41, part 4): the names to find them, the email
 * to book for them or give them a gift (D-048, D-073). Only the practitioner's own customers ever appear here.
 */
public record PanelCustomerView(long id, String firstName, String lastName, String email) {
}
