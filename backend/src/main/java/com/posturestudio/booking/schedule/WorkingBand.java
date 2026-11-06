package com.posturestudio.booking.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

/**
 * A working band of a weekly schedule: local times of day, e.g. MONDAY 09:00-13:00.
 * In and out of the panel API; the entity WorkingHours stores it.
 * Start and end on the hour or on the half hour (D-033); start before end and no overlaps
 * are checked on the whole version (ValidBands), because they need more than one field.
 */
public record WorkingBand(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull @OnTheHalfHour LocalTime startTime,
        @NotNull @OnTheHalfHour LocalTime endTime) {

    // Half-open intervals [start, end): 09-13 and 13-14 touch but do not overlap, as in the
    // exclusion constraint of V3 (D-036) and in the free slot calculation (D-035)
    boolean overlaps(WorkingBand other) {
        return dayOfWeek == other.dayOfWeek
                && startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }

    // the same rule as working_hours_start_time_ck and working_hours_end_time_ck
    static boolean isOnTheHalfHour(LocalTime time) {
        return (time.getMinute() == 0 || time.getMinute() == 30) && time.getSecond() == 0 && time.getNano() == 0;
    }
}
