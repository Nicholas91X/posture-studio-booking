package com.posturestudio.booking.schedule;

import java.util.List;

import com.posturestudio.booking.shared.TimeRange;

/**
 * When a practitioner works in a period, and when they are away (D-091): only times. The reason of an absence is
 * the practitioner's own note and never leaves this module through here.
 */
public record WorkingTime(List<TimeRange> working, List<TimeRange> absences) {

    public WorkingTime {
        working = List.copyOf(working);
        absences = List.copyOf(absences);
    }
}
