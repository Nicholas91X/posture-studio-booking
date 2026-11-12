package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// After the session (guide 46): the job that completes the sessions over, the practitioner who marks a no-show and
// corrects it within 7 days of the end, and "my sessions" split into upcoming and past. Today is Friday 6 November
// 2026, 10:00 in Rome; the session is Monday 9 November 9:00-10:00 in Rome (08:00-09:00 UTC). The job is called
// directly, with a clock that moves (D-047). In a transaction rolled back at the end
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class})
@Transactional
class BookingAfterSessionTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final Instant START = Instant.parse("2026-11-09T08:00:00Z");
    private static final Instant END = Instant.parse("2026-11-09T09:00:00Z");
    private static final Instant WINDOW_END = END.plus(Duration.ofDays(7));

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    BookingJobs jobs;

    @Autowired
    BookingService service;

    @Autowired
    EntityManager entityManager;

    private BookingTestData data;
    private long anna;
    private long annasUser;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaAndHerCustomer() {
        clock.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, anna);
        seduta = data.service(anna, 60);
        cliente = data.customer("cliente@example.com");
    }

    // ***** the job (guide 46, part 1) *****

    @Test
    void aConfirmedSessionOverIsCompletedByTheJob() {
        long booking = confirmed();
        clockAt(END.plusSeconds(1));

        jobs.completeSessionsOver();

        assertThat(statusOf(booking)).isEqualTo("COMPLETED");
        // the job leaves no mark: nobody marked it
        assertThat(jdbc.queryForObject("select attendance_marked_by from booking where id = ?", Long.class, booking)).isNull();
    }

    // "over" includes its last instant: ends_at <= now
    @Test
    void exactlyAtItsEndASessionIsOver() {
        long booking = confirmed();
        clockAt(END);

        jobs.completeSessionsOver();

        assertThat(statusOf(booking)).isEqualTo("COMPLETED");
    }

    @Test
    void aSessionInProgressIsNotCompleted() {
        long booking = confirmed();
        clockAt(END.minusSeconds(1));

        jobs.completeSessionsOver();

        assertThat(statusOf(booking)).isEqualTo("CONFIRMED");
    }

    // only CONFIRMED: the guard in the where. A no-show is the case the database alone would let through (a mark is
    // allowed on COMPLETED too): only the guard keeps it
    @Test
    void theJobDoesNotTurnANoShowIntoCompleted() {
        long noShow = confirmed();
        clockAt(START);
        assertThat(mark(noShow, "no-show")).hasStatusOk();
        entityManager.flush();
        clockAt(END.plus(Duration.ofHours(5)));

        jobs.completeSessionsOver();

        assertThat(statusOf(noShow)).isEqualTo("NO_SHOW");
    }

    // without the guard the database would refuse this one by itself: a cancellation keeps its "who"
    // (booking_cancellation_trace_ck, V16), and COMPLETED cannot have one
    @Test
    void theJobDoesNotTouchACancelledSession() {
        long late = confirmed();
        jdbc.update("update booking set status = 'CANCELLED_LATE', cancelled_at = now(), cancelled_by = ? where id = ?",
                annasUser, late);
        clockAt(END.plus(Duration.ofHours(5)));

        jobs.completeSessionsOver();

        assertThat(statusOf(late)).isEqualTo("CANCELLED_LATE");
    }

    // two runs, or two instances of the application: the second finds nothing left to change
    @Test
    void twoRunsDoTheWorkOnce() {
        confirmed();
        clockAt(END);

        assertThat(service.completeSessionsOver()).isEqualTo(1);
        assertThat(service.completeSessionsOver()).isZero();
    }

    // ***** the marks of the practitioner (guide 46, part 2) *****

    @Test
    void aNoShowCanBeMarkedFromTheStartOfTheSessionWithWhoAndWhen() {
        long booking = confirmed();
        clockAt(START);

        MvcTestResult result = mark(booking, "no-show");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("NO_SHOW");
        entityManager.flush();
        assertThat(jdbc.queryForObject("select attendance_marked_by from booking where id = ?", Long.class, booking))
                .isEqualTo(annasUser);
        assertThat(markedAt(booking)).isEqualTo(START);
    }

    @Test
    void aSessionCompletedByTheJobCanBeMarkedNoShowAndCorrectedBack() {
        long booking = confirmed();
        clockAt(END.plus(Duration.ofDays(1)));
        jobs.completeSessionsOver();

        assertThat(mark(booking, "no-show")).bodyJson().extractingPath("$.status").isEqualTo("NO_SHOW");
        clock.advance(Duration.ofHours(1));
        assertThat(mark(booking, "attended")).bodyJson().extractingPath("$.status").isEqualTo("COMPLETED");

        entityManager.flush();
        // the last mark
        assertThat(markedAt(booking)).isEqualTo(END.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
    }

    // the same mark again: the same answer, the first mark kept
    @Test
    void aSecondNoShowChangesNothing() {
        long booking = confirmed();
        clockAt(START);
        mark(booking, "no-show");
        clock.advance(Duration.ofHours(2));

        assertThat(mark(booking, "no-show")).hasStatusOk();

        entityManager.flush();
        assertThat(markedAt(booking)).isEqualTo(START);
    }

    // what was paid is used, in both directions: nothing moves in the ledger
    @Test
    void aNoShowDoesNotTouchTheCredits() {
        long lot = data.creditLot(anna, seduta, cliente, "2027-01-31", 5);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);
        clockAt(START);

        mark(booking, "no-show");
        mark(booking, "attended");

        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(4);
    }

    // ***** the window, to the second (guide 46, part 3) *****

    @Test
    void oneSecondBeforeTheStartNothingCanBeMarked() {
        long booking = confirmed();
        clockAt(START.minusSeconds(1));

        MvcTestResult result = mark(booking, "no-show");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_NOT_STARTED");
    }

    @Test
    void exactlySevenDaysAfterTheEndItCanStillBeMarked() {
        long booking = confirmed();
        clockAt(WINDOW_END);

        assertThat(mark(booking, "no-show")).hasStatusOk();
    }

    @Test
    void oneSecondLaterTheWindowIsClosed() {
        long booking = confirmed();
        clockAt(WINDOW_END.plusSeconds(1));

        MvcTestResult result = mark(booking, "no-show");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ATTENDANCE_WINDOW_CLOSED");
    }

    // ***** what cannot be marked *****

    @Test
    void theSessionOfAnotherPractitionerIsNotFound() {
        long marco = data.practitioner("marco@example.com");
        long booking = data.confirmed(marco, data.service(marco, 60), cliente, MONDAY_9, "CARD", null);
        clockAt(START);

        assertThat(mark(booking, "no-show")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCancelledSessionIsNotMarked() {
        long booking = confirmed();
        jdbc.update("update booking set status = 'CANCELLED_IN_TIME', cancelled_at = now(), cancelled_by = ? where id = ?",
                annasUser, booking);
        clockAt(START);

        MvcTestResult result = mark(booking, "no-show");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_TRANSITION_NOT_ALLOWED");
    }

    // ***** "my sessions", upcoming and past (guide 46, part 4) *****

    @Test
    void theCustomerSeesUpcomingAndPastSessionsEachInItsOrder() {
        long thursday = data.confirmed(anna, seduta, cliente, "2026-11-05T09:00+01:00", "CARD", null);
        long wednesday = data.confirmed(anna, seduta, cliente, "2026-11-04T09:00+01:00", "CARD", null);
        // in progress now (Friday 9:30-10:30): not over, so upcoming
        long now = data.confirmed(anna, seduta, cliente, "2026-11-06T09:30+01:00", "CARD", null);
        long tuesday = data.confirmed(anna, seduta, cliente, "2026-11-10T09:00+01:00", "CARD", null);
        long monday = confirmed();

        assertThat(list("?period=upcoming")).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) now, (int) monday, (int) tuesday);
        assertThat(list("?period=past")).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) thursday, (int) wednesday);
        // without the parameter, the list of before: all, the furthest in the future first
        assertThat(list("")).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) tuesday, (int) monday, (int) now, (int) thursday, (int) wednesday);
    }

    @Test
    void anUnknownPeriodIsABadRequest() {
        assertThat(list("?period=tomorrow")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ***** helpers *****

    private long confirmed() {
        return data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
    }

    private MvcTestResult mark(long bookingId, String what) {
        return mvc.post().uri("/api/panel/bookings/" + bookingId + "/" + what).with(sessions.practitioner(anna)).exchange();
    }

    private MvcTestResult list(String query) {
        return mvc.get().uri("/api/customer/bookings" + query).with(sessions.customer(cliente)).exchange();
    }

    private void clockAt(Instant instant) {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), instant));
    }

    private String statusOf(long bookingId) {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, bookingId);
    }

    private Instant markedAt(long bookingId) {
        return jdbc.queryForObject("select attendance_marked_at from booking where id = ?", OffsetDateTime.class,
                bookingId).toInstant();
    }
}
