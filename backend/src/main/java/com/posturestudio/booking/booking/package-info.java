/**
 * Module "booking": bookings, the payment hold, approval (D-030), and later cancellations.
 *
 * Step 7, piece 1: table booking (migration V4) with the exclusion constraint that makes overlapping
 * bookings impossible (D-013, D-042), and the implementation of schedule's OccupiedTime (D-044),
 * so that free slots leave out booked time. Requests, transitions and jobs arrive with the next pieces.
 *
 * The package is booking.booking because the root package of the application is already called booking.
 */
package com.posturestudio.booking.booking;
