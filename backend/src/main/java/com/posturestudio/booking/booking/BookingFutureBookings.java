package com.posturestudio.booking.booking;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.FutureBookings;

// The answer of booking to practitioner (D-077, guide 45, part 3): the sessions that stop a deactivation. The
// interface is practitioner's, so the dependency stays booking -> practitioner (as BookingOccupiedTime, D-044).
// It joins the transaction of the deactivation, which already holds the lock on the practitioner
@Component
class BookingFutureBookings implements FutureBookings {

    private final BookingService bookings;

    BookingFutureBookings(BookingService bookings) {
        this.bookings = bookings;
    }

    @Override
    @Transactional(readOnly = true)
    public long unfinishedOf(long practitionerId, Instant now) {
        return bookings.unfinished(practitionerId, now).size();
    }
}
