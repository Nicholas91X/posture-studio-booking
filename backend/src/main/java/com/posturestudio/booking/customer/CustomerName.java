package com.posturestudio.booking.customer;

/**
 * The name of a customer, for the panel of their own practitioner only (requirements, "Riservatezza del calendario").
 */
public record CustomerName(String firstName, String lastName) {

    static CustomerName from(Customer customer) {
        return new CustomerName(customer.getFirstName(), customer.getLastName());
    }
}
