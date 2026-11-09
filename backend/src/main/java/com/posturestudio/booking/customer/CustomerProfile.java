package com.posturestudio.booking.customer;

// The customer's own data, as they see it in their profile (guide 25, 7.3)
public record CustomerProfile(String email, String firstName, String lastName) {
}
