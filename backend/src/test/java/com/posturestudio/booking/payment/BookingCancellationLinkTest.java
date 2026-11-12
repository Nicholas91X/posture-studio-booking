package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.LedgerCleanup;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The cancellation of the customer with REAL commits (guide 43, parts 5, 6, 7): the payment link of a session to be
// paid in the studio is closed AFTER the commit, by a listener that only a real commit wakes; and the criterion of
// D-065 with the real path: link made by the panel API, session cancelled by the customer API, then the payment
// that Stripe completed anyway. Also the double click at the same instant. Friday 6 November 2026; the session is
// Friday 20 November, 9:00: in time. What was committed is deleted after each test
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class BookingCancellationLinkTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long anna;
    private long booking;
    private long customer;

    @BeforeEach
    void aSessionToBePaidInTheStudio() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("cancel-link-anna@example.com");
        data.assign(anna, "alpha");
        booking = data.booking(anna);
        customer = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        stripe.reset();
        Long customerUser = jdbc.queryForObject("select user_id from customer where id = ?", Long.class, customer);
        LedgerCleanup.delete(transactionManager, jdbc, sql -> {
            sql.update("delete from credit_movement where credit_lot_id in (select id from credit_lot where customer_id = ?)",
                    customer);
            sql.update("delete from credit_lot where customer_id = ?", customer);
        });
        jdbc.update("delete from stripe_event where event_id like 'evt_cancel_%'");
        jdbc.update("delete from payment where booking_id = ?", booking);
        jdbc.update("delete from booking where id = ?", booking);
        jdbc.update("delete from customer where id = ?", customer);
        jdbc.update("delete from app_user where id = ?", customerUser);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from app_user where email = 'cancel-link-anna@example.com'");
    }

    // ***** the open link is closed after the commit (guide 43, part 5) *****

    @Test
    void aCancellationClosesTheOpenLinkOnStripe() {
        String page = link();

        assertThat(cancel()).isEqualTo(200);

        assertThat(stripe.expiredSessions()).containsExactly(page);
        assertThat(paymentStatus(page)).isEqualTo("EXPIRED");
        assertThat(sessionRow()).containsEntry("status", "CANCELLED_IN_TIME").containsEntry("paid_with", "IN_STUDIO");
    }

    // the studio cancels it (guide 44): the same listener closes the page, whoever cancelled
    @Test
    void aCancellationOfTheStudioClosesTheOpenLinkToo() {
        String page = link();

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/cancel").with(sessions.practitioner(anna))
                .exchange()).hasStatusOk();

        assertThat(stripe.expiredSessions()).containsExactly(page);
        assertThat(paymentStatus(page)).isEqualTo("EXPIRED");
        assertThat(sessionRow()).containsEntry("status", "CANCELLED_BY_STUDIO");
    }

    // a compensation that fails changes nothing for the customer: the page expires by itself (guide 43, 5.2)
    @Test
    void withStripeDownTheCancellationSucceedsAndTheLinkStaysToExpireByItself() {
        String page = link();
        stripe.goDown();

        assertThat(cancel()).isEqualTo(200);

        assertThat(sessionRow()).containsEntry("status", "CANCELLED_IN_TIME");
        assertThat(paymentStatus(page)).isEqualTo("PENDING");
    }

    // ***** the criterion of D-065, with the real path (guide 43, 5.3) *****

    // the page was closed, but Stripe had completed it a moment before: the payment arrives, the hold is released
    @Test
    void aLinkPaidAfterTheCancellationIsReleasedAndTheSessionStaysCancelled() {
        String page = link();
        assertThat(cancel()).isEqualTo(200);

        assertThat(paid("evt_cancel_1", page, "pi_cancel_1")).isEqualTo(200);

        assertThat(stripe.captures()).isEmpty();
        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId).containsExactly("pi_cancel_1");
        assertThat(paymentStatus(page)).isEqualTo("CANCELED");
        assertThat(sessionRow()).containsEntry("status", "CANCELLED_IN_TIME").containsEntry("paid_with", "IN_STUDIO");
    }

    // the customer paid while the cancellation was on its way: the listener finds the page complete and leaves it;
    // the webhook then releases the hold
    @Test
    void aLinkPaidJustBeforeTheCancellationIsLeftToTheWebhookWhichReleasesIt() {
        String page = link();
        stripe.customerPaid(page);

        assertThat(cancel()).isEqualTo(200);
        assertThat(stripe.expiredSessions()).isEmpty();
        assertThat(paymentStatus(page)).isEqualTo("PENDING");

        assertThat(paid("evt_cancel_1", page, "pi_cancel_1")).isEqualTo(200);

        assertThat(stripe.captures()).isEmpty();
        assertThat(paymentStatus(page)).isEqualTo("CANCELED");
        assertThat(sessionRow()).containsEntry("status", "CANCELLED_IN_TIME");
    }

    // the other order: the link is paid FIRST, so the session is paid by card, and the cancellation in time gives a
    // credit of 1. The lock on the session puts the two in a row, and each sees the session as it really is
    @Test
    void aLinkPaidBeforeTheCancellationMakesItACardSessionThatGivesACredit() {
        String page = link();
        assertThat(paid("evt_cancel_1", page, "pi_cancel_1")).isEqualTo(200);
        assertThat(sessionRow()).containsEntry("paid_with", "CARD");

        assertThat(cancel()).isEqualTo(200);

        assertThat(stripe.captures()).extracting(FakeStripeGateway.Capture::paymentIntentId).containsExactly("pi_cancel_1");
        assertThat(stripe.cancels()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where customer_id = ? and source = 'CANCELLATION'",
                Long.class, customer)).isEqualTo(1);
    }

    // ***** the double click, at the same instant (guide 43, part 6) *****

    @Test
    void twoCancellationsAtTheSameInstantGiveOneCredit() throws Exception {
        jdbc.update("update booking set paid_with = 'CARD' where id = ?", booking);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return cancel();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return cancel();
            });
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsOnly(HttpStatus.OK.value());
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from credit_lot where customer_id = ?", Long.class, customer))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_movement where booking_id = ?", Long.class, booking))
                .isEqualTo(1);
    }

    // ***** helpers *****

    // a payment link made by the panel API, as Anna would: the id of its Stripe page
    private String link() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/payment-link").with(sessions.practitioner(anna))
                .exchange()).hasStatusOk();
        return jdbc.queryForObject("select stripe_checkout_session_id from payment where booking_id = ? and status = 'PENDING'",
                String.class, booking);
    }

    private int cancel() {
        return mvc.post().uri("/api/customer/bookings/" + booking + "/cancel").with(sessions.customer(customer))
                .exchange().getResponse().getStatus();
    }

    private int paid(String eventId, String sessionId, String paymentIntentId) {
        String body = StripeEvents.sessionCompleted(eventId, sessionId, paymentIntentId);
        return mvc.post().uri("/api/webhooks/stripe/alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeEvents.signature(body, ALPHA_SECRET, FixedClockConfiguration.NOW))
                .content(body)
                .exchange()
                .getResponse()
                .getStatus();
    }

    private String paymentStatus(String sessionId) {
        return jdbc.queryForObject("select status from payment where stripe_checkout_session_id = ?", String.class,
                sessionId);
    }

    private java.util.Map<String, Object> sessionRow() {
        return jdbc.queryForMap("select status, paid_with from booking where id = ?", booking);
    }
}
