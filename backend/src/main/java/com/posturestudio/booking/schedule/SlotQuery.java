package com.posturestudio.booking.schedule;

import static com.posturestudio.booking.shared.Checks.requireOneOf;
import static com.posturestudio.booking.shared.Checks.requirePositive;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.posturestudio.booking.shared.TimeRange;

/**
 * Everything the free slot calculation needs, with a name for each value (guide 16, part 2):
 * nine values, three of them int, would be easy to swap in a method call.
 *
 * versions: the bands of each schedule version, by the date it starts from (D-034).
 * busy: the ranges already taken, whatever the reason (absences; bookings from step 7).
 * from, to: the local days requested; the calculation cuts them to notice and horizon.
 * now: passed in, never read from the system, so the calculation stays pure (D-035).
 */
record SlotQuery(
        NavigableMap<LocalDate, List<WorkingBand>> versions,
        List<TimeRange> busy,
        Duration duration,
        int slotStepMinutes,
        int minBookingNoticeHours,
        int bookingHorizonDays,
        LocalDate from,
        LocalDate to,
        Instant now) {

    // a query that cannot make sense never reaches the loop. The API checks earlier, with a 400
    SlotQuery {
        Objects.requireNonNull(versions, "versions must not be null");
        Objects.requireNonNull(busy, "busy must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("duration must be positive, was " + duration);
        }
        requireOneOf(slotStepMinutes, Set.of(30, 60), "slotStepMinutes");
        requirePositive(minBookingNoticeHours, "minBookingNoticeHours");
        requirePositive(bookingHorizonDays, "bookingHorizonDays");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to, was " + from + " - " + to);
        }
        // defensive copies: the query cannot change after it is built
        versions = Collections.unmodifiableNavigableMap(new TreeMap<>(versions));
        busy = List.copyOf(busy);
    }
}
