package com.posturestudio.booking.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.posturestudio.booking.shared.TimeRange;

import jakarta.persistence.LockModeType;

// "Holding the time at instant now" appears in two queries below, with the same two lines:
// a holding state, and not a pending payment whose hold has already expired (guide 18, 3.5)
interface BookingRepository extends JpaRepository<Booking, Long> {

    // The ranges that hold the time for whoever looks at instant now, overlapping the window
    // (starts before its end, ends after its start). The constructor expression builds the records
    // directly, without loading the entities. The index (practitioner_id, starts_at) of V4 serves it
    @Query("""
            select new com.posturestudio.booking.shared.TimeRange(b.startsAt, b.endsAt)
            from Booking b
            where b.practitionerId = :practitionerId
              and b.startsAt < :windowEnd and b.endsAt > :windowStart
              and b.status in :holding
              and (b.status <> :pending or b.holdExpiresAt > :now)
            order by b.startsAt
            """)
    List<TimeRange> findOccupied(long practitionerId, Instant windowStart, Instant windowEnd, Instant now,
                                 Collection<BookingStatus> holding, BookingStatus pending);

    // The future bookings of a customer with a practitioner that count in the limit (D-023, D-041):
    // starting after now, holding the time at now. Panel bookings count too: booked_by is not looked at.
    // The index (customer_id, starts_at) of V4 serves it
    @Query("""
            select count(b) from Booking b
            where b.customerId = :customerId and b.practitionerId = :practitionerId
              and b.startsAt > :now
              and b.status in :holding
              and (b.status <> :pending or b.holdExpiresAt > :now)
            """)
    long countFutureHolding(long customerId, long practitionerId, Instant now,
                            Collection<BookingStatus> holding, BookingStatus pending);

    // The lazy expiry (guide 19, part 5): PENDING_PAYMENT -> EXPIRED for the expired holds that overlap
    // a range, before a new booking of that range. The where is the guard of the transition: under
    // concurrency (the job of piece 4) a second update of the same row re-reads it and changes nothing.
    // A bulk update skips the entity and its @Version: the version is incremented here by hand
    @Modifying
    @Query("""
            update Booking b set b.status = :expired, b.version = b.version + 1
            where b.practitionerId = :practitionerId
              and b.status = :pending and b.holdExpiresAt <= :now
              and b.startsAt < :end and b.endsAt > :start
            """)
    int expireStaleHolds(long practitionerId, Instant start, Instant end, Instant now,
                         BookingStatus pending, BookingStatus expired);

    // ***** for the jobs (guide 21) *****

    // all the expired holds at once: the lazy expiry above without the range. Same guard in the where
    @Modifying
    @Query("""
            update Booking b set b.status = :expired, b.version = b.version + 1
            where b.status = :pending and b.holdExpiresAt <= :now
            """)
    int expireAllStaleHolds(Instant now, BookingStatus pending, BookingStatus expired);

    // only the ids: each one is then closed in a transaction of its own, after a lock and a fresh look
    @Query("""
            select b.id from Booking b
            where b.status = :waiting and b.approvalDeadline <= :now
            order by b.approvalDeadline
            """)
    List<Long> findIdsPastDeadline(Instant now, BookingStatus waiting);

    // ownership is part of the query: someone else's booking is not found, like a missing one
    Optional<Booking> findByIdAndCustomerId(Long id, Long customerId);

    Optional<Booking> findByIdAndPractitionerId(Long id, Long practitionerId);

    // ***** for transitions: select ... for update (guide 20, 3.4) *****
    // Whoever arrives second waits, then reads the new status: a transition that calls Stripe starts only
    // when it is surely ours. The lazy expiry and the jobs (bulk updates) wait on the same row lock

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booking> findLockedById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booking> findLockedByIdAndPractitionerId(Long id, Long practitionerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Booking> findLockedByIdAndCustomerId(Long id, Long customerId);
}
