package com.posturestudio.booking.auth;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface LoginCodeRepository extends JpaRepository<LoginCode, Long> {

    // The latest code of a destination, locked (select ... for update) until the transaction ends:
    // two verifications of the same code go one at a time, and each sees the attempts counted by the
    // other. Without the lock, parallel guesses would lose updates of the counter (guide 24, 4.5).
    // The id breaks the tie between two codes requested in the same instant
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LoginCode> findFirstByChannelAndDestinationOrderByCreatedAtDescIdDesc(
            LoginChannel channel, String destination);

    // the per-destination limit: codes requested after "since" (guide 24, part 6)
    @Query("""
            select new com.posturestudio.booking.auth.WindowUsage(count(c), min(c.createdAt))
            from LoginCode c
            where c.channel = :channel and c.destination = :destination and c.createdAt > :since""")
    WindowUsage usageOfDestination(@Param("channel") LoginChannel channel,
                                   @Param("destination") String destination,
                                   @Param("since") Instant since);

    // the per-IP limit: requestedFrom is already the /64 network for IPv6, so an equality is enough
    @Query("""
            select new com.posturestudio.booking.auth.WindowUsage(count(c), min(c.createdAt))
            from LoginCode c
            where c.requestedFrom = :from and c.createdAt > :since""")
    WindowUsage usageOfAddress(@Param("from") InetAddress from, @Param("since") Instant since);

    // one delete in bulk, no entity loaded: the cleanup of guide 24, 6.5
    @Modifying
    @Query("delete from LoginCode c where c.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}
