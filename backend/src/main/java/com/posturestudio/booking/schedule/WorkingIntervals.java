package com.posturestudio.booking.schedule;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The working hours of some days as intervals (D-091): for each day, the bands of the version valid that day (D-034),
 * converted on Rome's clock day by day, as FreeSlots does (D-035). A pure function: no clock, no database.
 */
final class WorkingIntervals {

    private WorkingIntervals() {
    }

    static List<TimeRange> of(TreeMap<LocalDate, List<WorkingBand>> versions, LocalDate from, LocalDate to) {
        List<TimeRange> working = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            // the version with the latest start date not after the day; none yet: the day has no hours
            Map.Entry<LocalDate, List<WorkingBand>> version = versions.floorEntry(day);
            if (version == null) {
                continue;
            }
            for (WorkingBand band : version.getValue()) {
                if (band.dayOfWeek() == day.getDayOfWeek()) {
                    // ZonedDateTime.of resolves a time that does not exist (DST gap) by moving it forward
                    working.add(new TimeRange(ZonedDateTime.of(day, band.startTime(), StudioTime.ZONE).toInstant(),
                            ZonedDateTime.of(day, band.endTime(), StudioTime.ZONE).toInstant()));
                }
            }
        }
        // the bands of a version are not necessarily in order (afternoon before morning)
        working.sort(Comparator.comparing(TimeRange::start));
        return List.copyOf(working);
    }
}
