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
}
