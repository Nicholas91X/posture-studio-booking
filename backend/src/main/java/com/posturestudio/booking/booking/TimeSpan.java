package com.posturestudio.booking.booking;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/** A span of time in an answer, on Rome's clock (D-035): from, to. */
public record TimeSpan(OffsetDateTime start, OffsetDateTime end) {

    static TimeSpan of(TimeRange range) {
        return new TimeSpan(StudioTime.inStudioZone(range.start()), StudioTime.inStudioZone(range.end()));
    }

    static TimeSpan of(Instant start, Instant end) {
        return new TimeSpan(StudioTime.inStudioZone(start), StudioTime.inStudioZone(end));
    }
}
