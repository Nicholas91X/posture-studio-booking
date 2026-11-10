package com.posturestudio.booking.customer;

/**
 * How to write to a customer (guide 27, 5.4): the address of their account and their names.
 * For notification only, which writes to the customer's own mailbox.
 */
public record CustomerContact(String email, String firstName, String lastName) {
}
