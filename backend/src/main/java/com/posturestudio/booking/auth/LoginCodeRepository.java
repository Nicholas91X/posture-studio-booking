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
    Optional<LoginCode> findFirstByChannelAndDestinationAndPurposeOrderByCreatedAtDescIdDesc(
            LoginChannel channel, String destination, CodePurpose purpose);

    // the same, for a verification: the latest code this account asked for this number (guide 29, 3.3).
    // Another account asking for the same number does not make this one useless
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LoginCode> findFirstByChannelAndDestinationAndPurposeAndRequestedByOrderByCreatedAtDescIdDesc(
            LoginChannel channel, String destination, CodePurpose purpose, Long requestedBy);

    // the per-account limit of the verifications (guide 29, 5.2)
    @Query("""
            select new com.posturestudio.booking.auth.WindowUsage(count(c), min(c.createdAt))
            from LoginCode c
            where c.purpose = com.posturestudio.booking.auth.CodePurpose.VERIFY_PHONE
              and c.requestedBy = :userId and c.createdAt > :since""")
    WindowUsage usageOfVerifications(@Param("userId") long userId, @Param("since") Instant since);

    // the daily cap: the SMS that really left, not the rows (guide 29, 4.3), whoever asked
    @Query("""
            select new com.posturestudio.booking.auth.WindowUsage(count(c), min(c.createdAt))
            from LoginCode c
            where c.channel = com.posturestudio.booking.auth.LoginChannel.SMS
              and c.messageSent = true and c.createdAt > :since""")
    WindowUsage usageOfSms(@Param("since") Instant since);

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
