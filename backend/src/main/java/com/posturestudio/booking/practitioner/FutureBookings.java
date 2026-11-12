package com.posturestudio.booking.practitioner;

import java.time.Instant;

/**
 * What practitioner needs to know of the bookings before a deactivation (D-077, guide 45, part 3), in its own
 * words. Declared here and implemented by the booking module, which already depends on practitioner: the
 * dependency stays one way, as schedule's OccupiedTime (D-044).
 */
public interface FutureBookings {

    // the sessions of this practitioner that hold the time and are not over at this instant: pending approval,
    // confirmed, and pending payment with a hold still valid (guide 45, part 2)
    long unfinishedOf(long practitionerId, Instant now);
}
