package com.posturestudio.booking.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The free slot calculation (D-035): a pure function. It reads no database and no clock:
 * everything comes in the SlotQuery, the result is the list of sessions that can start,
 * in order of start. ScheduleService loads the data and calls it (piece 4).
 *
 * A slot is free if it lies inside a working band of the version valid that day, starts on the
 * grid of the band (start, start + step, ...), overlaps nothing busy, starts more than
 * minBookingNoticeHours after now (D-030) and falls within bookingHorizonDays (D-023).
 *
 * Time: days and band times are local ("wall clock") and become instants one day at a time;
 * inside a band everything is an instant. No LocalTime arithmetic (it wraps at midnight),
 * and never "instant + 24 hours" across days (it shifts by one hour around DST changes).
 */
final class FreeSlots {

    private FreeSlots() {
    }

    static List<TimeRange> compute(SlotQuery query) {
        ZoneId zone = StudioTime.ZONE;
        LocalDate today = LocalDate.ofInstant(query.now(), zone);
        // strictly after: "more than 24 hours", so at 10:00 the slot of 10:00 tomorrow is out
        Instant earliestStart = query.now().plus(Duration.ofHours(query.minBookingNoticeHours()));
        // cut before looping: the work never depends on what the client asks for (guide 16, 5.3)
        LocalDate firstDay = max(query.from(), today);
        LocalDate lastDay = min(query.to(), today.plusDays(query.bookingHorizonDays()));
        Duration step = Duration.ofMinutes(query.slotStepMinutes());

        List<TimeRange> free = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            // the version with the latest start date not after the day (D-034); none yet: no slots
            Map.Entry<LocalDate, List<WorkingBand>> version = query.versions().floorEntry(day);
            if (version == null) {
                continue;
            }
            for (WorkingBand band : version.getValue()) {
                if (band.dayOfWeek() != day.getDayOfWeek()) {
                    continue;
                }
                // ZonedDateTime.of resolves a time that does not exist (DST gap) by moving it forward
                Instant bandEnd = ZonedDateTime.of(day, band.endTime(), zone).toInstant();
                for (Instant start = ZonedDateTime.of(day, band.startTime(), zone).toInstant();
                     !start.plus(query.duration()).isAfter(bandEnd);
                     start = start.plus(step)) {
                    TimeRange candidate = new TimeRange(start, start.plus(query.duration()));
                    if (start.isAfter(earliestStart) && isFree(candidate, query.busy())) {
                        free.add(candidate);
                    }
                }
            }
        }
        // the bands of a version are not necessarily in order (afternoon before morning)
        free.sort(Comparator.comparing(TimeRange::start));
        return List.copyOf(free);
    }

    // every candidate against every busy range: O(candidates x busy), a few thousand comparisons
    // at most with a 21-day horizon. A sweep over two sorted lists would be O(n + m), not worth it here
    private static boolean isFree(TimeRange candidate, List<TimeRange> busy) {
        for (TimeRange taken : busy) {
            if (candidate.overlaps(taken)) {
                return false;
            }
        }
        return true;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
