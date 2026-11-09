/**
 * Module "customer": the studio's customers.
 *
 * Step 7, piece 2: the Customer entity on the table of V1, the lock that serializes the booking
 * requests of one customer (CustomerService.lockForBooking), and the provisional current customer
 * (DevHeaderCurrentCustomer, to be replaced at step 8).
 * Step 7, piece 5: creation from the panel (D-024, CustomerService.findOrCreate).
 * TODO (step 8): creation at the first sign-in (D-014).
 */
package com.posturestudio.booking.customer;
