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
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.payment.FakeStripeGateway;

import jakarta.persistence.EntityManager;

// The studio cancels one of its future sessions (D-075, guide 44): the customer loses nothing, whatever the notice.
// Today is Friday 6 November 2026, 10:00 in Rome; the session is Monday 9 November, 9:00, free for the customer to
// cancel until Sunday 8 November, 9:00. In a transaction rolled back at the end; the link closed after the commit
// is in BookingCancellationLinkTest
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class})
@Transactional
@RecordApplicationEvents
class BookingStudioCancellationApiTest {

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

    // ***** a confirmed session, whatever the notice (guide 44, part 2) *****

    // the case that tells the two rules apart: for the customer this would be late, and give nothing
    @Test
    void withinTheNoticeACardSessionStillGivesACredit() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
        clockAt(FREE_UNTIL.plus(Duration.ofHours(12)));

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.booking.status").isEqualTo("CANCELLED_BY_STUDIO");
        // 8 November + 90 days
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-06");
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
        assertThat(stripe.cancels()).isEmpty();
    }

    @Test
    void aCreditGoesBackToItsLot() {
        long lot = data.creditLot(anna, seduta, cliente, "2027-03-31", 5);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.returnedCredit.lotId").asNumber().isEqualTo((int) lot);
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "CANCELLATION_RESTORE 1 " + lot);
    }

    // a credit already extended once, which the customer could not extend again: the studio can (D-076)
    @Test
    void anExtendedCreditThatEndsSoonGetsTheMinimumValidityAgain() {
        long lot = jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, valid_until, created_at)
                values (?, ?, ?, 'CANCELLATION', '2026-11-20', now()) returning id
                """, Long.class, cliente, anna, seduta);
        jdbc.update("insert into credit_movement (credit_lot_id, amount, reason, created_at) values (?, 1, 'LOT_CREATED', now())",
                lot);
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CREDIT", lot);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.returnedCredit.validUntil").isEqualTo("2027-02-04");
        assertThat(cancellationLotsOf(cliente)).isEqualTo(2);
    }

    @Test
    void aSessionToBePaidInTheStudioGivesNothingBack() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "IN_STUDIO", null);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.booking.status").isEqualTo("CANCELLED_BY_STUDIO");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit").isNull();
    }

    @Test
    void theCancellationIsSignedByThePractitionerAndAnnounced() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);

        cancel(booking);
        entityManager.flush();

        assertThat(jdbc.queryForObject("select cancelled_by from booking where id = ?", Long.class, booking))
                .isEqualTo(jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, anna));
        assertThat(events.stream(BookingCancelled.class)).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(BookingStatus.CANCELLED_BY_STUDIO);
            assertThat(event.creditValidUntil()).isEqualTo(java.time.LocalDate.of(2027, 2, 4));
        });
    }

    // ***** a request in wait: the rejection of always (D-078, guide 44, part 2) *****

    @Test
    void aCardRequestInWaitIsRejectedAndItsHoldReleased() {
        long booking = data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_APPROVAL");

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.booking.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit").isNull();
        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId)
                .containsExactly("pi_test_seed_" + booking);
        assertThat(events.stream(BookingRejected.class)).hasSize(1);
        assertThat(events.stream(BookingCancelled.class)).isEmpty();
    }

    @Test
    void aCreditRequestInWaitIsRejectedAndItsCreditBack() {
        long lot = data.creditLot(anna, seduta, cliente, "2027-03-31", 5);
        long booking = creditRequest(lot);

        MvcTestResult result = cancel(booking);

        assertThat(result).bodyJson().extractingPath("$.booking.status").isEqualTo("REJECTED");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit.lotId").asNumber().isEqualTo((int) lot);
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "BOOKING_RELEASE 1 " + lot);
    }

    // the same rule as /reject: after the deadline the request is already void, the job closes it
    @Test
    void aRequestPastItsDeadlineIsNotAnsweredAnyMore() {
        long booking = data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_APPROVAL");
        clock.advance(Duration.ofDays(1).plusHours(1));

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_DEADLINE_PASSED");
    }

    // ***** what cannot be cancelled *****

    @Test
    void theSessionOfAnotherPractitionerIsNotFound() {
        long marco = data.practitioner("marco@example.com");
        long booking = data.confirmed(marco, data.service(marco, 60), cliente, MONDAY_9, "CARD", null);

        assertThat(cancel(booking)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void fromItsStartASessionCannotBeCancelled() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
        clockAt(START);

        MvcTestResult result = cancel(booking);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_ALREADY_STARTED");
    }

    // the customer is paying: nothing for the studio to cancel, the hold expires by itself
    @Test
    void aRequestBeingPaidIsNotCancelled() {
        long booking = data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_PAYMENT");

        assertThat(cancel(booking)).hasStatus(HttpStatus.CONFLICT);
    }

    // the customer was faster: not the studio's result, so not "done"
    @Test
    void aSessionTheCustomerCancelledMeanwhileIsNotCancelledAgain() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
        assertThat(mvc.post().uri("/api/customer/bookings/" + booking + "/cancel").with(sessions.customer(cliente))
                .exchange()).hasStatusOk();

        assertThat(cancel(booking)).hasStatus(HttpStatus.CONFLICT);
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
    }

    // ***** the second click (guide 44, part 4) *****

    @Test
    void aSecondCancellationAnswersTheSameAndGivesNothingMore() throws Exception {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
        MvcTestResult first = cancel(booking);

        MvcTestResult second = cancel(booking);

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(cancellationLotsOf(cliente)).isEqualTo(1);
        assertThat(events.stream(BookingCancelled.class)).hasSize(1);
    }

    @Test
    void aSecondCancellationOfARequestAnswersTheSame() {
        long booking = data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_APPROVAL");
        cancel(booking);

        MvcTestResult second = cancel(booking);

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(second).bodyJson().extractingPath("$.booking.status").isEqualTo("REJECTED");
        assertThat(stripe.cancels()).hasSize(1);
    }

    // ***** helpers *****

    // a request of the customer with a credit, waiting for Anna until Saturday
    private long creditRequest(long lot) {
        long id = jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status, booked_by,
                                     paid_with, price_cents, approval_deadline, free_cancellation_until)
                values (?, ?, ?, ?, ?, 'PENDING_APPROVAL', 'CUSTOMER', 'CREDIT', 3000, ?, ?)
                returning id
                """, Long.class, cliente, anna, seduta, BookingTestData.at(MONDAY_9),
                BookingTestData.at(MONDAY_9).plusHours(1), BookingTestData.at(BookingTestData.LATER),
                BookingTestData.noticeBefore(MONDAY_9));
        jdbc.update("insert into credit_movement (credit_lot_id, amount, reason, booking_id, created_at) values (?, -1, 'BOOKING', ?, now())",
                lot, id);
        return id;
    }

    private MvcTestResult cancel(long bookingId) {
        return mvc.post().uri("/api/panel/bookings/" + bookingId + "/cancel").with(sessions.practitioner(anna)).exchange();
    }

    private void clockAt(Instant instant) {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), instant));
    }

    private long cancellationLotsOf(long customerId) {
        return jdbc.queryForObject("select count(*) from credit_lot where customer_id = ? and source = 'CANCELLATION'",
                Long.class, customerId);
    }
}
