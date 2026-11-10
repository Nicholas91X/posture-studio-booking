/**
 * Module "customer": the studio's customers.
 *
 * Step 7, piece 2: the Customer entity on the table of V1, and the lock that serializes the booking
 * requests of one customer (CustomerService.lockForBooking).
 * Step 7, piece 5: creation from the panel (D-024, CustomerService.findOrCreate).
 * Step 8, piece 2: the current customer from the signed-in user (SessionCurrentCustomer), and the profile,
 * which creates the customer row after the first sign-in (D-050, guide 25, 7.3).
 */
package com.posturestudio.booking.customer;
