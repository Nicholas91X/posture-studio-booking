package com.posturestudio.booking.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.posturestudio.booking.shared.TimeRange;

interface BookingRepository extends JpaRepository<Booking, Long> {

    // The ranges that hold the time for whoever looks at instant now: overlapping the window
    // (starts before its end, ends after its start), in a holding state, and not a pending payment
    // whose hold has already expired (guide 18, 3.5). The constructor expression builds the records
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
}
