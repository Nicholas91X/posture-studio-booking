package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.payment.FakeStripeGateway;

import jakarta.persistence.EntityManager;

// Booking with a credit, from outside (guide 38): real sessions, the real services, the fake Stripe to prove it
// is never asked. Today is Friday 6 November 2026, 10:00 in Rome; the session is Monday 9 November, 9:00.
// Anna approves by hand (the default, D-030), within 12 hours: by Friday 22:00
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class BookingWithCreditTest {

    private static final String MONDAY_9 = "2026-11-09T09:00:00+01:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    BookingService bookingService;

    @Autowired
    EntityManager entityManager;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaWorksAndHerCustomerHasCredits() {
        stripe.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("cliente@example.com");
    }

    // ***** the request (guide 38, part 4) *****

    @Test
    void aRequestWithACreditWaitsForThePractitionerWithoutStripe() throws Exception {
        long later = data.creditLot(anna, seduta, cliente, "2027-01-31", 5);
        long sooner = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_APPROVAL");
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isNull();
        assertThat(result).bodyJson().extractingPath("$.holdExpiresAt").isNull();
        // the deadline of a paid request: request + 12 hours (D-030)
        assertThat(result).bodyJson().extractingPath("$.approvalDeadline").isEqualTo("2026-11-06T22:00:00+01:00");
        // the lot that ends first (D-010), and nothing asked of Stripe
        long booking = idOf(result);
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + sooner);
        assertThat(data.balance(sooner)).isEqualTo(4);
        assertThat(data.balance(later)).isEqualTo(5);
        assertThat(stripe.calls()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from payment where booking_id = ?", Long.class, booking)).isZero();
    }

    // one path for both modes (D-030): nothing to capture, so it is confirmed at once
    @Test
    void theNoticeOfCancellationIsPhotographedInARequestWithACredit() throws Exception {
        data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        jdbc.update("update practitioner set cancellation_notice_hours = 48 where id = ?", anna);

        long booking = idOf(request(MONDAY_9));
        jdbc.update("update practitioner set cancellation_notice_hours = 72 where id = ?", anna);

        assertThat(noticeIs(booking, 48)).isTrue();
    }

    // the notice of the practitioner photographed at birth (V16, guide 42, part 6): changing the setting
    // afterwards moves nothing
    private boolean noticeIs(long bookingId, int hours) {
        return jdbc.queryForObject(
                "select free_cancellation_until = starts_at - make_interval(hours => ?) from booking where id = ?",
                Boolean.class, hours, bookingId);
    }

    @Test
    void inAutomaticModeACreditConfirmsAtOnce() throws Exception {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(data.balance(lot)).isEqualTo(4);
        assertThat(stripe.captures()).isEmpty();
    }

    // ***** the answer of the practitioner, and the customer giving up (guide 38, part 7) *****

    @Test
    void anApprovalConfirmsWithoutCapturing() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        long booking = idOf(request(MONDAY_9));

        MvcTestResult result = mvc.post().uri("/api/panel/bookings/" + booking + "/approve")
                .with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(data.balance(lot)).isEqualTo(4);
        assertThat(stripe.captures()).isEmpty();
    }

    @Test
    void aRejectionGivesTheCreditBackToTheSameLot() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        long booking = idOf(request(MONDAY_9));

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk();

        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "BOOKING_RELEASE 1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(5);
        assertThat(stripe.cancels()).isEmpty();
    }

    @Test
    void aWithdrawalGivesTheCreditBack() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        long booking = idOf(request(MONDAY_9));

        assertThat(mvc.post().uri("/api/customer/bookings/" + booking + "/withdraw").with(sessions.customer(cliente)))
                .hasStatusOk();

        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "BOOKING_RELEASE 1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // the job of the expired answers (D-047): its deadline moved into the past, then the job's own step
    @Test
    void anUnansweredRequestGivesTheCreditBack() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        long booking = idOf(request(MONDAY_9));
        entityManager.flush();
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:00:00Z' where id = ?", booking);
        entityManager.clear();

        bookingService.expireApproval(booking);
        entityManager.flush();

        assertThat(jdbc.queryForObject("select status from booking where id = ?", String.class, booking))
                .isEqualTo("APPROVAL_EXPIRED");
        assertThat(data.movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "BOOKING_RELEASE 1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    @Test
    void theCustomerSeesTheBookingPaidWithACredit() throws Exception {
        data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        long booking = idOf(request(MONDAY_9));

        assertThat(mvc.get().uri("/api/customer/bookings/" + booking).with(sessions.customer(cliente)))
                .bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
    }

    // ***** when the credit is not used *****

    // guide 38, part 2: valid until Sunday 8 November, the session is on Monday 9: it goes by card
    @Test
    void aCreditThatEndsBeforeTheSessionIsNotUsed() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-11-08", 5);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_PAYMENT");
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("CARD");
        assertThat(data.balance(lot)).isEqualTo(5);
        assertThat(stripe.calls()).hasSize(1);
    }

    // the last valid day is the day of the session: used
    @Test
    void aCreditThatEndsOnTheDayOfTheSessionIsUsed() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-11-09", 1);

        assertThat(request(MONDAY_9)).bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
        assertThat(data.balance(lot)).isZero();
    }

    @Test
    void aCreditOfAnotherServiceIsNotUsed() throws Exception {
        long valutazione = data.service(anna, 60);
        long lot = data.creditLot(anna, valutazione, cliente, "2026-12-31", 5);

        assertThat(request(MONDAY_9)).bodyJson().extractingPath("$.paidWith").isEqualTo("CARD");
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // ***** what stops a request *****

    // a practitioner without a payment account takes the bookings of a customer with a credit (a gift, piece 5)
    @Test
    void withoutAPaymentAccountACreditStillBooks() throws Exception {
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        data.creditLot(anna, seduta, cliente, "2026-12-31", 5);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
    }

    @Test
    void withoutAPaymentAccountAndWithoutACreditNothingIsBooked() throws Exception {
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CARD_PAYMENTS_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from booking where customer_id = ?", Long.class, cliente)).isZero();
    }

    // D-023 holds with a credit too: it protects the calendar, not the money
    @Test
    void theLimitOfFutureBookingsHoldsWithACredit() throws Exception {
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);
        data.booking(anna, seduta, cliente, "2026-11-10T09:00+01:00", "2026-11-10T10:00+01:00", "CONFIRMED");

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_LIMIT_REACHED");
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // ***** helpers *****

    private MvcTestResult request(String startsAt) {
        return mvc.post().uri("/api/customer/bookings").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d, "startsAt": "%s"}
                        """.formatted(anna, seduta, startsAt))
                .exchange();
    }

    private static long idOf(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
