package com.posturestudio.booking.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface UnavailabilityRepository extends JpaRepository<Unavailability, Long> {

    // ownership is part of the query (see WorkingScheduleRepository)
    Optional<Unavailability> findByIdAndPractitionerId(Long id, Long practitionerId);

    // panel: those not over yet, in order of start
    List<Unavailability> findByPractitionerIdAndEndsAtAfterOrderByStartsAt(Long practitionerId, Instant now);

    // free slots: the absences overlapping [windowStart, windowEnd), i.e. starts_at < windowEnd and
    // ends_at > windowStart. Parameters follow the method name: the END of the window comes first
    List<Unavailability> findByPractitionerIdAndStartsAtBeforeAndEndsAtAfter(
            Long practitionerId, Instant windowEnd, Instant windowStart);
}
