package com.posturestudio.booking.credit;

import java.time.LocalDate;

// one row of CreditMovementRepository.findReturnsTo: the credit that came back to a booking, and where
record BookingReturn(long bookingId, long lotId, LocalDate validUntil) {
}
