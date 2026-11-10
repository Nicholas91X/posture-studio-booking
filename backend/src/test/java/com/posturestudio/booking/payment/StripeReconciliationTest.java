package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// "Stripe captured, our commit failed" (guide 33, part 6), with REAL commits and rollbacks: the fake Stripe
// captures and then makes our transaction fail. Not @Transactional, so that the failed approval really rolls
// back. What was committed is deleted after each test. Same context as the other API tests
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public class StripeReconciliationTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private long anna;
    private long booking;
    private String paymentIntent;

    @BeforeEach
    void anApprovalCapturesThenOurCommitFails() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("reconcile-anna@example.com");
        data.assign(anna, "alpha");
        booking = data.waitingBooking(anna, "reconcile-cliente@example.com", "alpha");
        paymentIntent = "pi_" + booking;

        stripe.failAfterCapture();
        int status = mvc.post().uri("/api/panel/bookings/" + booking + "/approve").with(sessions.practitioner(anna))
                .exchange().getResponse().getStatus();

        // the money moved on Stripe, our side rolled back: the two systems disagree
        assertThat(status).isEqualTo(500);
        assertThat(stripe.captures()).hasSize(1);
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");
        stripe.reset();
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        stripe.reset();
        jdbc.update("delete from stripe_event where event_id like 'evt_reconcile_%'");
        jdbc.update("delete from payment where booking_id = ?", booking);
        Long customer = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
        jdbc.update("delete from booking where id = ?", booking);
        jdbc.update("delete from customer where id = ?", customer);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from app_user where email like 'reconcile-%'");
    }

    // Stripe tells us it captured: the booking is confirmed WITHOUT capturing again
    @Test
    void paymentIntentSucceededConfirmsTheBookingWithoutCapturingAgain() {
        assertThat(post(succeeded("evt_reconcile_1"))).isEqualTo(200);

        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        assertThat(stripe.captures()).isEmpty();
    }

    // the rare case of part 6, point 4: in the meantime the practitioner rejected. The money is captured for a
    // booking that is not confirmed: never confirmed behind the practitioner's back, logged for a refund by hand
    @Test
    void aCapturedPaymentOfARejectedBookingIsRecordedAndTheBookingStaysRejected() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk();

        // the release found the PaymentIntent captured: not cancelable, the payment left as it was
        assertThat(paymentStatus()).isEqualTo("AUTHORIZED");

        assertThat(post(succeeded("evt_reconcile_1"))).isEqualTo(200);

        // the truth about the money, and the booking as the practitioner decided (the log asks for a refund)
        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(bookingStatus()).isEqualTo("REJECTED");
    }

    // the practitioner, seeing the error, presses "approve" again: the SAME idempotency key, so Stripe gives
    // back the first capture instead of taking the money twice
    @Test
    void approvingAgainUsesTheSameIdempotencyKey() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/approve").with(sessions.practitioner(anna)))
                .hasStatusOk();

        assertThat(stripe.captures()).extracting(FakeStripeGateway.Capture::idempotencyKey)
                .containsExactly("capture-" + paymentIntent);
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        // and when payment_intent.succeeded arrives, there is nothing left to do
        assertThat(post(succeeded("evt_reconcile_1"))).isEqualTo(200);
        assertThat(paymentStatus()).isEqualTo("SUCCEEDED");
    }

    private String succeeded(String eventId) {
        return StripeEvents.event(eventId, "payment_intent.succeeded", """
                {"id": "%s", "object": "payment_intent", "status": "succeeded", "amount": 3000,
                 "metadata": {"booking_id": "%d"}}
                """.formatted(paymentIntent, booking));
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

    private String bookingStatus() {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, booking);
    }

    private String paymentStatus() {
        return jdbc.queryForObject("select status from payment where booking_id = ?", String.class, booking);
    }
}
