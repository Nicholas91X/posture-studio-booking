package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.booking.CardHolds;

import jakarta.persistence.EntityManager;

// D-064: a release that failed because Stripe did not answer is tried again by the job, with growing pauses,
// then given up. The clock moves on command and the job is called directly: no real minute is ever waited.
// The fake Stripe comes from TestcontainersConfiguration
@DataJpaTest
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, PaymentConfiguration.class,
        StripeCardHolds.class, PaymentReleases.class, PaymentJobs.class})
public class PaymentReleaseRetryTest {

    private static final Instant NOW = FixedClockConfiguration.NOW;

    @Autowired
    CardHolds cards;

    @Autowired
    PaymentJobs jobs;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    private long booking;
    private String paymentIntent;

    @BeforeEach
    void aRequestOfAnnaWasJustRejectedWhileStripeIsDown() {
        clock.reset();
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        long anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        booking = data.waitingBooking(anna, "cliente@example.com", "alpha");
        jdbc.update("update booking set status = 'REJECTED' where id = ?", booking);
        paymentIntent = "pi_" + booking;

        stripe.goDown();
        cards.cancel(booking);
    }

    @Test
    void aFailedReleaseIsScheduledAgainInAMinute() {
        assertThat(payment()).containsEntry("status", "AUTHORIZED").containsEntry("release_attempts", 1);
        assertThat(nextReleaseAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
    }

    @Test
    void theJobWaitsForTheRightTimeThenReleasesWhenStripeIsBack() {
        // 30 seconds later: not yet
        clock.advance(Duration.ofSeconds(30));
        jobs.retryReleases();
        assertThat(payment()).containsEntry("release_attempts", 1);

        // after a minute: tried again, still down, the pause grows to 5 minutes
        clock.advance(Duration.ofSeconds(30));
        jobs.retryReleases();
        assertThat(payment()).containsEntry("release_attempts", 2);
        assertThat(nextReleaseAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

        // Stripe is back; 4 minutes later still not the time, at 5 the release goes through
        stripe.reset();
        clock.advance(Duration.ofMinutes(4));
        jobs.retryReleases();
        assertThat(stripe.cancels()).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        jobs.retryReleases();

        // a NEW key for every retry: Stripe would give the saved failure back for the same key (D-064)
        assertThat(stripe.cancels()).containsExactly(
                new FakeStripeGateway.Cancel("alpha", paymentIntent, "cancel-" + paymentIntent + "-2"));
        assertThat(payment()).containsEntry("status", "CANCELED");
        assertThat(nextReleaseAt()).isNull();
    }

    @Test
    void afterFiveFailuresTheJobGivesUp() {
        // the first failure was at the rejection; then the pauses: 1 minute, 5, 30, 2 hours
        for (Duration pause : Payment.RELEASE_RETRY_PAUSES) {
            clock.advance(pause);
            jobs.retryReleases();
        }

        assertThat(payment()).containsEntry("status", "AUTHORIZED").containsEntry("release_attempts", 5);
        assertThat(nextReleaseAt()).isNull();

        // given up: a day later, even with Stripe back, nobody tries again (the hold dissolves by itself)
        stripe.reset();
        clock.advance(Duration.ofDays(1));
        jobs.retryReleases();
        assertThat(stripe.cancels()).isEmpty();
        assertThat(payment()).containsEntry("release_attempts", 5);
    }

    // released meanwhile by Stripe's own event (payment_intent.canceled): the job finds nothing owed
    @Test
    void aPaymentReleasedMeanwhileIsNotTriedAgain() {
        flushed();
        jdbc.update("update payment set status = 'CANCELED', next_release_at = null where booking_id = ?", booking);
        entityManager.clear();
        stripe.reset();

        clock.advance(Duration.ofMinutes(1));
        jobs.retryReleases();

        assertThat(stripe.cancels()).isEmpty();
    }

    // V13: only a hold still in place can owe a release
    @Test
    void theDatabaseRefusesAReleaseOwedByAPaymentNoLongerHeld() {
        flushed();

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update payment set status = 'CANCELED' where booking_id = ?", booking));
    }

    private void flushed() {
        entityManager.flush();
    }

    private Map<String, Object> payment() {
        flushed();
        return jdbc.queryForMap("select status, release_attempts from payment where booking_id = ?", booking);
    }

    private Instant nextReleaseAt() {
        flushed();
        java.sql.Timestamp at = jdbc.queryForObject("select next_release_at from payment where booking_id = ?",
                java.sql.Timestamp.class, booking);
        return at == null ? null : at.toInstant();
    }
}
