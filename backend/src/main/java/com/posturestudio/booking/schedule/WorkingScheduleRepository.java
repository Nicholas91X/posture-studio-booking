package com.posturestudio.booking.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// The repository of the aggregate: there is none for WorkingHours
interface WorkingScheduleRepository extends JpaRepository<WorkingSchedule, Long> {

    // ownership is part of the query: someone else's version and a missing one look the same
    Optional<WorkingSchedule> findByIdAndPractitionerId(Long id, Long practitionerId);

    // panel: all versions with their bands in one query (no N+1). left: a version without bands
    // is listed too. Since Hibernate 6 no distinct is needed to avoid repeated versions
    @Query("""
            select s from WorkingSchedule s left join fetch s.bands
            where s.practitionerId = :practitionerId
            order by s.validFrom
            """)
    List<WorkingSchedule> findForPanel(Long practitionerId);

    // the Java side of working_schedule_practitioner_valid_from_uk, for a clear error
    boolean existsByPractitionerIdAndValidFrom(Long practitionerId, LocalDate validFrom);

    boolean existsByPractitionerIdAndValidFromAndIdNot(Long practitionerId, LocalDate validFrom, Long id);
}
