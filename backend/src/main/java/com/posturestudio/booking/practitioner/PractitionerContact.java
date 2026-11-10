package com.posturestudio.booking.practitioner;

/**
 * How to write to a practitioner, and how to name them to their customers (guide 27, 5.4).
 * The email is the account's (auth): for notification only, never in a public answer.
 */
public record PractitionerContact(String email, String firstName, String lastName) {
}
