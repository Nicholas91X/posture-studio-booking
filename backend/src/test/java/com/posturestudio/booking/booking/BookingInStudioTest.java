package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.payment.FakeStripeGateway;

import jakarta.persistence.EntityManager;

// A practitioner without a payment account (D-089): the request of a customer is paid in the studio, never touches
// Stripe, and follows the approval like a request paid with a credit. Today is Friday 6 November 2026, 10:00 in
// Rome; the session is Monday 9 November, 9:00. Anna approves by hand (the default, D-030), within 12 hours
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class BookingInStudioTest {

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
    void annaWorksWithoutAnAccount() {
        stripe.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("cliente@example.com");
    }

    @Test
    void theRequestIsBornInTheStudioAndWaitsForThePractitioner() throws Exception {
        MvcTestResult result = request(MONDAY_9);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_APPROVAL");
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("IN_STUDIO");
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isNull();
        assertThat(result).bodyJson().extractingPath("$.holdExpiresAt").isNull();
        assertThat(result).bodyJson().extractingPath("$.approvalDeadline").isEqualTo("2026-11-06T22:00:00+01:00");
        long booking = idOf(result);
        assertThat(jdbc.queryForObject("select booked_by from booking where id = ?", String.class, booking))
                .isEqualTo("CUSTOMER");
        // no Stripe, no payment row: the system does not record cash (D-089)
        assertThat(stripe.calls()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from payment where booking_id = ?", Long.class, booking)).isZero();
    }

    @Test
    void inAutomaticModeItIsConfirmedAtOnce() throws Exception {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("IN_STUDIO");
        assertThat(stripe.calls()).isEmpty();
    }

    @Test
    void anApprovalConfirmsWithNothingToCapture() throws Exception {
        long booking = idOf(request(MONDAY_9));

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/approve").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(stripe.captures()).isEmpty();
    }

    @Test
    void aRejectionHasNothingToGiveBack() throws Exception {
        long booking = idOf(request(MONDAY_9));

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(stripe.cancels()).isEmpty();
        assertThat(data.movementsOf(booking)).isEmpty();
    }

    @Test
    void aWithdrawalAndAnUnansweredRequestHaveNothingToGiveBack() throws Exception {
        long withdrawn = idOf(request(MONDAY_9));
        assertThat(mvc.post().uri("/api/customer/bookings/" + withdrawn + "/withdraw").with(sessions.customer(cliente)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("WITHDRAWN");

        long unanswered = idOf(request("2026-11-10T09:00:00+01:00"));
        entityManager.flush();
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:00:00Z' where id = ?", unanswered);
        entityManager.clear();
        bookingService.expireApproval(unanswered);
        entityManager.flush();

        assertThat(jdbc.queryForObject("select status from booking where id = ?", String.class, unanswered))
                .isEqualTo("APPROVAL_EXPIRED");
        assertThat(stripe.cancels()).isEmpty();
    }

    // the rules of "in the studio" (D-040): in time, no credit, because nothing was paid
    @Test
    void aCancellationInTimeGivesNoCredit() throws Exception {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        long booking = idOf(request(MONDAY_9));

        MvcTestResult result = mvc.post().uri("/api/customer/bookings/" + booking + "/cancel")
                .with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED_IN_TIME");
        assertThat(result).bodyJson().extractingPath("$.returnedCredit").isNull();
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where customer_id = ?", Long.class, cliente)).isZero();
    }

    // a credit of the customer is used all the same (D-071): the account decides only what is not covered
    @Test
    void aCreditIsStillUsedFirst() throws Exception {
        long lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 5);

        MvcTestResult result = request(MONDAY_9);

        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
        assertThat(data.balance(lot)).isEqualTo(4);
    }

    // the limit of future bookings holds: with no money at stake, it is one of the defences left (D-089)
    @Test
    void theLimitOfFutureBookingsHolds() throws Exception {
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        idOf(request(MONDAY_9));

        MvcTestResult second = request("2026-11-10T09:00:00+01:00");

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("BOOKING_LIMIT_REACHED");
    }

    // the account given back later (D-089): new requests go by card again, the one in the studio stays as it was
    @Test
    void withAnAccountAgainTheNextRequestGoesByCard() throws Exception {
        long inStudio = idOf(request(MONDAY_9));
        jdbc.update("insert into practitioner_payment_account (practitioner_id, account, assigned_at) values (?, 'alpha', now())",
                anna);

        MvcTestResult byCard = request("2026-11-10T09:00:00+01:00");

        assertThat(byCard).bodyJson().extractingPath("$.paidWith").isEqualTo("CARD");
        assertThat(byCard).bodyJson().extractingPath("$.status").isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select paid_with from booking where id = ?", String.class, inStudio))
                .isEqualTo("IN_STUDIO");
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
