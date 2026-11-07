package com.posturestudio.booking.booking;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.schedule.OccupiedTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The booked time, for schedule's free slot calculation (D-044).
 * A small class of its own, depending only on the repository: if the booking service implemented
 * OccupiedTime, the cycle schedule -> booking service -> schedule would stop Spring at startup
 * (guide 18, 8.5): BookingService depends on ScheduleService to check a requested slot.
 */
@Component
class BookingOccupiedTime implements OccupiedTime {

    private final BookingRepository bookings;

    BookingOccupiedTime(BookingRepository bookings) {
        this.bookings = bookings;
    }

    // joins the read-only transaction of ScheduleService.freeSlots
    @Override
    @Transactional(readOnly = true)
    public List<TimeRange> between(long practitionerId, TimeRange window, Instant now) {
        return bookings.findOccupied(practitionerId, window.start(), window.end(), now,
                BookingStatus.HOLDS_TIME, BookingStatus.PENDING_PAYMENT);
    }
}
