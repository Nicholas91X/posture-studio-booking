package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingTestDoubles.RecordingCardHolds;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;

// The two jobs of guide 21, with a clock the test moves forward: no real minute is waited.
// The jobs are called directly: this proves what they do, not that Spring calls them every minute.
// The clock starts on Friday 6 November 2026, 10:00 in Rome; Anna answers within 12 hours (default)
@DataJpaTest
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, BookingService.class, com.posturestudio.booking.credit.CreditService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class, BookingJobs.class})
@RecordApplicationEvents
public class BookingJobsTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";

    @Autowired
    BookingJobs jobs;

    @Autowired
    BookingService service;

    @Autowired
    MovableClock clock;

    @Autowired
    RecordingCardHolds cards;

    @Autowired
    ApplicationEvents events;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long seduta;
    private long cliente;
    private long altroCliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        clock.reset();
        cards.reset();
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        cliente = data.customer("cliente@example.com");
        altroCliente = data.customer("altro@example.com");
        data.weekdays(anna, "2026-11-02");
    }

    // ***** payment holds *****

    @Test
    void aHoldIsNotTouchedOneMinuteBeforeItEnds() {
        long id = request(cliente);
        clock.advance(Duration.ofMinutes(31));

        jobs.expireHolds();

        assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aHoldEndingNowIsExpired() {
        // hold_expires_at <= now: at 10:32 a hold until 10:32 is over (32 minutes, D-061)
        long id = request(cliente);
        clock.advance(Duration.ofMinutes(32));

        jobs.expireHolds();

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("version", 1L);
    }

    @Test
    void aPaidRequestIsNotTouchedByTheHoldsJob() {
        long id = request(cliente);
        clock.advance(Duration.ofMinutes(10));
        service.paymentAuthorized(id);
        clock.advance(Duration.ofMinutes(50));

        jobs.expireHolds();

        assertThat(statusOf(id)).isEqualTo("PENDING_APPROVAL");
    }

    // ***** unanswered requests *****

    @Test
    void aRequestIsStillWaitingOneMinuteBeforeTheDeadline() {
        long id = paid();
        clock.advance(Duration.ofHours(12).minusMinutes(1));

        jobs.expireApprovals();

        assertThat(statusOf(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void aRequestPastTheDeadlineExpiresReleasesTheHoldAndFreesTheSlot() {
        // requested at 10:00, deadline at 22:00
        long id = paid();
        clock.advance(Duration.ofHours(12));

        jobs.expireApprovals();

        assertThat(statusOf(id)).isEqualTo("APPROVAL_EXPIRED");
        assertThat(cards.calls).containsExactly("cancel " + id);
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(ApprovalExpired.class);
        // the slot left the exclusion constraint: another customer can book it
        assertThat(request(altroCliente)).isPositive();
    }

    @Test
    void anApprovedRequestIsNotTouchedAfterTheDeadline() {
        long id = paid();
        service.approve(anna, id);
        cards.reset();
        clock.advance(Duration.ofHours(13));

        jobs.expireApprovals();

        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void aRequestWithdrawnMeanwhileIsLeftAloneWithoutError() {
        // the list said "past deadline", but before the lock the customer withdrew: after the lock the
        // guard is read again and nothing happens (guide 21, 3.2). This is also what a second instance sees
        long id = paid();
        clock.advance(Duration.ofHours(12));
        service.withdraw(cliente, id);
        cards.reset();

        Assertions.assertDoesNotThrow(() -> service.expireApproval(id));

        assertThat(statusOf(id)).isEqualTo("WITHDRAWN");
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void runningTheJobTwiceReleasesTheHoldOnce() {
        // two instances, or two runs: the second one finds the request already closed
        long id = paid();
        clock.advance(Duration.ofHours(12));

        jobs.expireApprovals();
        jobs.expireApprovals();

        assertThat(cards.calls).containsExactly("cancel " + id);
    }

    // ***** helpers *****

    private long request(long customerId) {
        return service.request(customerId, new NewBooking(anna, seduta, OffsetDateTime.parse(MONDAY_9))).id();
    }

    // requested at 10:00 and paid at once, MANUAL mode; the arrangement's events are forgotten
    private long paid() {
        long id = request(cliente);
        service.paymentAuthorized(id);
        events.clear();
        return id;
    }

    // what the commit would do, so that SQL reads see the changes and later reads see the database
    private Map<String, Object> row(long id) {
        entityManager.flush();
        entityManager.clear();
        return jdbc.queryForMap("select status, version from booking where id = ?", id);
    }

    private String statusOf(long id) {
        return (String) row(id).get("status");
    }
}
