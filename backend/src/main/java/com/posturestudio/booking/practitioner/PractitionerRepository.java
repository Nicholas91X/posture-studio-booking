package com.posturestudio.booking.practitioner;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PractitionerRepository extends JpaRepository<Practitioner, Long> {

    List<Practitioner> findAllByOrderByLastNameAscFirstNameAsc();

    // practitioner.user_id is unique (V1): at most one. For the signed-in practitioner (guide 25, 7.2)
    Optional<Practitioner> findByUserId(Long userId);

    // The two locks of the race between a deactivation and a new booking (guide 45, part 4), in native SQL with
    // the clause written out: @Lock(PESSIMISTIC_WRITE) may become FOR NO KEY UPDATE, which does NOT conflict with
    // FOR KEY SHARE, and the race would stay open.
    // The deactivation: FOR UPDATE, the one lock that conflicts with FOR KEY SHARE
    @Query(value = "select id from practitioner where id = :id for update", nativeQuery = true)
    Optional<Long> lockForDeactivation(long id);

    // Every new booking: FOR KEY SHARE, the lightest one, which two bookings of the same practitioner can hold
    // together (the one the insert of a booking takes anyway for its foreign key)
    @Query(value = "select id from practitioner where id = :id for key share", nativeQuery = true)
    Optional<Long> lockForBooking(long id);

}
