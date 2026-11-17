package com.posturestudio.booking.booking;

import java.util.List;

/**
 * One practitioner's agenda as the others see it (D-091): who, and only times. busy: what holds the time now
 * (bookings in a state that occupies it, D-049), without saying what; working: the working hours of the period;
 * absences: when they are away, without the reason. A record of its own: a field added here is seen by the other
 * practitioners, and a test checks the answer field by field.
 */
public record AgendaView(long practitionerId, String firstName, String lastName, List<TimeSpan> busy,
                         List<TimeSpan> working, List<TimeSpan> absences) {
}
