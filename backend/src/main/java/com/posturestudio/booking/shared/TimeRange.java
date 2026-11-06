package com.posturestudio.booking.shared;

import java.time.Instant;
import java.util.Objects;

/**
 * A half-open range of time [start, end): start included, end excluded. Used for absences,
 * free slots and, from step 7, bookings: the same convention as the exclusion constraints
 * of the database, so Java and PostgreSQL agree on what overlaps (D-035).
 * A reversed or empty range cannot exist.
 */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end, was " + start + " - " + end);
        }
    }

    // 10-11 and 11-12 touch but do not overlap: two sessions one after the other are possible
    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
