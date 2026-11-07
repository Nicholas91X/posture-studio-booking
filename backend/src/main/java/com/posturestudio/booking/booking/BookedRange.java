package com.posturestudio.booking.booking;

import java.time.Instant;

import com.posturestudio.booking.shared.TimeRange;

/**
 * A booking of ANOTHER practitioner, as the room flag needs it (guide 23, 2.3): which service, when.
 * No customer, no id, nothing else: the query that builds it does not even read them, so nothing about
 * the other practitioner's customers can leak (privacy by construction).
 * Public only because Hibernate builds it with a constructor expression; it never leaves BookingService.
 */
public record BookedRange(long serviceId, Instant start, Instant end) {

    TimeRange range() {
        return new TimeRange(start, end);
    }
}
