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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// The webhook with REAL commits and rollbacks (guide 32, parts 5 and 8): an error of ours must not lose the
// event, and two deliveries at the same moment must have one effect. NOT @Transactional: the requests must
// commit on their own, and in parallel. What was committed is deleted after each test. Same context as
// StripeWebhookApiTest
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public class StripeWebhookRollbackTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private long anna;
    private long booking;
    private long payment;

    @BeforeEach
    void aCustomerOfAnnaIsOnTheStripePage() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("webhook-anna@example.com");
        data.assign(anna, "alpha");
        booking = data.pendingBooking(anna, "webhook-cliente@example.com");
        payment = data.payment(booking, "alpha", "PENDING", "cs_test_R");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        stripe.reset();
        jdbc.update("delete from stripe_event where event_id like 'evt_rollback_%'");
        jdbc.update("delete from payment where booking_id = ?", booking);
        Long customer = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
        jdbc.update("delete from booking where id = ?", booking);
        jdbc.update("delete from customer where id = ?", customer);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from app_user where email like 'webhook-%'");
    }

    // "an error of ours must not lose the event" (brief): the release of a late payment fails, everything
    // rolls back, "seen" included; Stripe sends the same event again, and this time it goes through
    @Test
    void aFailureRollsBackTheSeenEventSoThatStripesRetryIsProcessed() {
        jdbc.update("update booking set status = 'EXPIRED' where id = ?", booking);
        String event = StripeEvents.sessionCompleted("evt_rollback_1", "cs_test_R", "pi_R");
        stripe.goDown();

        int first = post(event);

        // not a 2xx: for Stripe, "try again"
        assertThat(first).isEqualTo(503);
        assertThat(jdbc.queryForObject("select count(*) from stripe_event where event_id = 'evt_rollback_1'", Long.class)).isZero();
        assertThat(paymentStatus()).isEqualTo("PENDING");

        stripe.reset();
        int retry = post(event);

        assertThat(retry).isEqualTo(200);
        assertThat(paymentStatus()).isEqualTo("CANCELED");
        assertThat(stripe.cancels()).hasSize(1);
    }

    @Test
    void theSameEventDeliveredTwiceAtTheSameMomentHasOneEffect() throws Exception {
        String event = StripeEvents.sessionCompleted("evt_rollback_1", "cs_test_R", "pi_R");

        List<Integer> statuses = together(event, event);

        assertThat(statuses).containsOnly(200);
        assertOneHeldPaymentAndNoRelease();
    }

    // two DIFFERENT events for the same session at the same moment (guide 32, 5.2): the second waits for the
    // row lock of the payment, reads the condition again, finds it no longer PENDING, and stops there
    @Test
    void twoEventsForTheSameSessionAtTheSameMomentHaveOneEffectAndReleaseNothing() throws Exception {
        List<Integer> statuses = together(StripeEvents.sessionCompleted("evt_rollback_1", "cs_test_R", "pi_R"),
                StripeEvents.sessionCompleted("evt_rollback_2", "cs_test_R", "pi_R"));

        assertThat(statuses).containsOnly(200);
        assertOneHeldPaymentAndNoRelease();
    }

    private void assertOneHeldPaymentAndNoRelease() {
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("select version from payment where id = ?", Long.class, payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from booking where id = ?", String.class, booking))
                .isEqualTo("PENDING_APPROVAL");
        assertThat(stripe.cancels()).isEmpty();
    }

    // both requests released at the same instant
    private List<Integer> together(String first, String second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return post(first);
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return post(second);
            });
            start.countDown();
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private int post(String body) {
        return mvc.post().uri("/api/webhooks/stripe/alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeEvents.signature(body, ALPHA_SECRET, FixedClockConfiguration.NOW))
                .content(body)
                .exchange()
                .getResponse()
                .getStatus();
    }

    private String paymentStatus() {
        return jdbc.queryForObject("select status from payment where id = ?", String.class, payment);
    }
}
