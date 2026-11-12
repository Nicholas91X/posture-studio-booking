package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.booking.BookingEvent.BookingCancelled;
import com.posturestudio.booking.payment.FakeStripeGateway;

import jakarta.persistence.EntityManager;

// The customer cancels a confirmed session (guide 43), from outside: real sessions, the real services, a clock that
// moves. Today is Friday 6 November 2026, 10:00 in Rome; the session is Monday 9 November, 9:00, with the notice of
// 24 hours photographed: free cancellation until Sunday 8 November, 9:00 in Rome (08:00 UTC), that instant included.
// In a transaction rolled back at the end: the closing of a payment link after the commit is in
// BookingCancellationLinkTest
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class})
@Transactional
@RecordApplicationEvents
class BookingCancellationApiTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final Instant FREE_UNTIL = Instant.parse("2026-11-08T08:00:00Z");
    private static final Instant START = Instant.parse("2026-11-09T08:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    ApplicationEvents events;

    @Autowired
    EntityManager entityManager;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaAndHerCustomer() {
        clock.reset();
        stripe.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        cliente = data.customer("cliente@example.com");
    }

    // ***** the boundary of the notice, to the second (guide 43, part 3.2) *****

    @Test
    void oneSecondBeforeTheEndOfTheNoticeIsInTime() {
        long booking = card();
        clockAt(FREE_UNTIL.minusSeconds(1));

        assertThat(cancel(booking)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_IN_TIME");
    }

    // the row that tells <= from <: "until" includes its last instant
    @Test
    void exactlyAtTheEndOfTheNoticeIsStillInTime() {
        long booking = card();
        clockAt(FREE_UNTIL);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_IN_TIME");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-06");
    }

    @Test
    void oneSecondAfterTheEndOfTheNoticeIsLate() {
        long booking = card();
        clockAt(FREE_UNTIL.plusSeconds(1));

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_LATE");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit").isNull();
        assertThat(cancellationLotsOf(cliente)).isZero();
    }

    @Test
    void oneSecondBeforeTheStartALateCancellationIsStillPossible() {
        long booking = card();
        clockAt(START.minusSeconds(1));

        assertThat(cancel(booking)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_LATE");
    }

    @Test
    void fromItsStartASessionCannotBeCancelled() {
        long booking = card();
        clockAt(START);

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_ALREADY_STARTED");
        assertThat(statusOf(booking)).isEqualTo("CONFIRMED");
    }

    // ***** what comes back (guide 43, part 4.1) *****

    // with a credit whose lot lasts long enough: +1 on the same lot
    @Test
    void inTimeACreditGoesBackToItsLot() {
        long lot = data.creditLot(anna, seduta, cliente, "2027-03-31", 5);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.lotId").asNumber().isEqualTo((int) lot);
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-03-31");
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "CANCELLATION_RESTORE 1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // a package that ends in two weeks: the credit gets 90 days from today in a lot of its own (D-076)
    @Test
    void inTimeACreditOfALotThatEndsSoonGetsTheMinimumValidity() {
        long lot = data.creditLot(anna, seduta, cliente, "2026-11-20", 5);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-04");
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
        assertThat(data.balance(lot)).isEqualTo(4);
    }

    @Test
    void lateACreditIsLost() {
        long lot = data.creditLot(anna, seduta, cliente, "2027-03-31", 5);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);
        clockAt(FREE_UNTIL.plusSeconds(1));

        assertThat(cancel(booking)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_LATE");
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(4);
    }

    // paid by card: a new credit of 1, valid 90 days from today; Stripe is not asked anything (the money is captured)
    @Test
    void inTimeACardSessionGivesACreditOfOne() {
        long booking = card();

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-04");
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
        assertThat(stripe.cancels()).isEmpty();
        assertThat(stripe.captures()).isEmpty();
    }

    // nothing was paid (D-040): nothing comes back, in time or not
    @Test
    void aSessionToBePaidInTheStudioGivesNothingBack() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "IN_STUDIO", null);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_IN_TIME");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit").isNull();
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where customer_id = ?", Long.class, cliente))
                .isZero();
    }

    // ***** the second loop (D-076, guide 42, part 8): a credit born from a card is not extended again *****

    @Test
    void aCreditBornFromACardIsNotExtendedByASecondCancellation() throws Exception {
        long first = card();
        MvcTestResult firstCancellation = cancel(first);
        long lot = ((Number) com.jayway.jsonpath.JsonPath.read(
                firstCancellation.getResponse().getContentAsString(), "$.returnedCredit.lotId")).longValue();
        // 85 days later, Saturday 30 January 2027: a session on Tuesday 2 February with that credit, cancelled in time
        clock.advance(Duration.ofDays(85));
        long second = data.confirmed(anna, seduta, cliente, "2027-02-02T09:00+01:00", "CREDIT", lot);

        MvcTestResult result = cancel(second);

        // back to its lot, with the day it had: 4 February, not 30 January + 90
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.lotId").asNumber().isEqualTo((int) lot);
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-04");
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
    }

    // ***** who can cancel what (guide 43, part 2) *****

    @Test
    void theSessionOfAnotherCustomerIsNotFound() {
        long other = data.customer("altro@example.com");
        long booking = data.confirmed(anna, seduta, other, MONDAY_9, "CARD", null);

        assertThat(cancel(booking)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(statusOf(booking)).isEqualTo("CONFIRMED");
    }

    // a request waiting for the practitioner is withdrawn, not cancelled (D-030)
    @Test
    void aRequestInWaitIsNotCancelled() {
        long booking = data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_APPROVAL");

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_TRANSITION_NOT_ALLOWED");
    }

    // the studio cancelled it, not the customer: the customer is not told "done" (guide 43, part 6)
    @Test
    void aSessionCancelledByTheStudioIsNotCancelledAgain() {
        long booking = card();
        jdbc.update("""
                update booking set status = 'CANCELLED_BY_STUDIO', cancelled_at = now(),
                       cancelled_by = (select user_id from practitioner where id = ?) where id = ?
                """, anna, booking);

        assertThat(cancel(booking)).hasStatus(HttpStatus.CONFLICT);
    }

    // ***** the second click (guide 43, part 6) *****

    @Test
    void aSecondCancellationAnswersTheSameAndGivesNothingMore() throws Exception {
        long booking = card();
        MvcTestResult first = cancel(booking);

        MvcTestResult second = cancel(booking);

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(second).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_IN_TIME");
        assertThat(second).bodyJson().extractingPath("$.returnedCredit")
                .isEqualTo(com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.returnedCredit"));
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
        assertThat(events.stream(BookingCancelled.class)).hasSize(1);
    }

    // ***** the trace, the event, the views *****

    @Test
    void theCancellationSaysWhoAndWhen() {
        long booking = card();
        clock.advance(Duration.ofHours(3));

        cancel(booking);
        // the change of the entity reaches the database at the flush (at the commit, outside a test): the check of
        // the trace (booking_cancellation_trace_ck) runs here too
        entityManager.flush();

        assertThat(jdbc.queryForObject("select cancelled_by from booking where id = ?", Long.class, booking))
                .isEqualTo(jdbc.queryForObject("select user_id from customer where id = ?", Long.class, cliente));
        assertThat(jdbc.queryForObject("select cancelled_at from booking where id = ?", java.time.OffsetDateTime.class,
                booking).toInstant()).isEqualTo(Instant.parse("2026-11-06T12:00:00Z"));
    }

    @Test
    void theEventCarriesWhatCameBack() {
        long booking = card();

        cancel(booking);

        assertThat(events.stream(BookingCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.bookingId()).isEqualTo(booking);
            assertThat(event.status()).isEqualTo(BookingStatus.CANCELLED_IN_TIME);
            assertThat(event.paidWith()).isEqualTo(PaidWith.CARD);
            assertThat(event.creditValidUntil()).isEqualTo(java.time.LocalDate.of(2027, 2, 4));
        });
    }

    // "my sessions" shows until when it is free to cancel, and what came back (guide 43, part 9)
    @Test
    void theListShowsTheNoticeAndTheCreditThatCameBack() {
        long cancelled = card();
        long coming = data.confirmed(anna, seduta, cliente, "2026-11-10T09:00+01:00", "CARD", null);
        cancel(cancelled);

        MvcTestResult list = mvc.get().uri("/api/customer/bookings").with(sessions.customer(cliente)).exchange();

        assertThat(list).bodyJson().extractingPath("$[?(@.id == " + coming + ")].freeCancellationUntil")
                .asArray().containsExactly("2026-11-09T09:00:00+01:00");
        assertThat(list).bodyJson().extractingPath("$[?(@.id == " + coming + ")].returnedCredit")
                .asArray().containsExactly((Object) null);
        assertThat(list).bodyJson().extractingPath("$[?(@.id == " + cancelled + ")].returnedCredit.validUntil")
                .asArray().containsExactly("2027-02-04");
    }

    // ***** helpers *****

    private long card() {
        return data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
    }

    private MvcTestResult cancel(long bookingId) {
        return mvc.post().uri("/api/customer/bookings/" + bookingId + "/cancel").with(sessions.customer(cliente))
                .exchange();
    }

    private void clockAt(Instant instant) {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), instant));
    }

    private String statusOf(long bookingId) {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, bookingId);
    }

    private long cancellationLotsOf(long customerId) {
        return jdbc.queryForObject("select count(*) from credit_lot where customer_id = ? and source = 'CANCELLATION'",
                Long.class, customerId);
    }
}
