package com.posturestudio.booking.schedule;

import java.time.Instant;
import java.util.List;

import com.posturestudio.booking.shared.TimeRange;

/**
 * What the free slot calculation needs to know besides absences, in the words of schedule:
 * which ranges of a practitioner are taken. Declared here, implemented by the booking module (D-044):
 * the code depends one way only, booking -> schedule, and Spring injects the implementation.
 * schedule does not know who fills these ranges, so nothing about them can reach the public API.
 *
 * A single method: tests of schedule replace it with a lambda.
 */
@FunctionalInterface
public interface OccupiedTime {

    /**
     * The ranges of the practitioner that overlap the window and are taken at the instant now.
     * now is passed, not read: ScheduleService reads the clock once for the whole calculation.
     */
    List<TimeRange> between(long practitionerId, TimeRange window, Instant now);
}
