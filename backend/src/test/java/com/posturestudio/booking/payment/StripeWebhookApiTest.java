package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// The webhook from outside (guide 32, part 11): real signatures computed by hand, the real controller and
// service, the fake Stripe for the releases. No session and NO CSRF token in any request: Stripe has neither.
// The rows are written with SQL, so that no entity of the test's transaction is already in memory.
// Today is Friday 6 November 2026, 10:00 in Rome: the signatures are made at that instant
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class StripeWebhookApiTest {

    // the webhook secrets of the test configuration (src/test/resources/config/application.properties)
    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";
    private static final String BETA_SECRET = "whsec_beta_not_a_real_secret";
    private static final Instant NOW = FixedClockConfiguration.NOW;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    EntityManager entityManager;

    private long anna;
    private long booking;
    private long payment;

    @BeforeEach
    void aCustomerOfAnnaIsOnTheStripePage() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        booking = data.pendingBooking(anna, "cliente@example.com");
        payment = data.payment(booking, "alpha", "PENDING", "cs_test_A");
    }

    // ***** the payment *****

    @Test
    void aCompletedSessionHoldsThePaymentAndTheRequestWaitsForThePractitioner() {
        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "AUTHORIZED").containsEntry("stripe_payment_intent_id", "pi_A");
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForMap("select account, type from stripe_event where event_id = 'evt_1'"))
                .containsEntry("account", "alpha").containsEntry("type", "checkout.session.completed");
        assertThat(stripe.cancels()).isEmpty();
    }

    @Test
    void inAutomaticModeThePaymentConfirmsTheBooking() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);

        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatusOk();

        persisted();
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        // captured for real now (guide 33), on the account of the payment
        assertThat(stripe.captures()).containsExactly(new FakeStripeGateway.Capture("alpha", "pi_A", "capture-pi_A"));
        assertThat(paymentRow()).containsEntry("status", "SUCCEEDED");
    }

    // guide 33, part 4: a refusal for good is not an error for Stripe to retry for three days; the request goes
    // to the practitioner as in manual mode
    @Test
    void inAutomaticModeARefusedCaptureLeavesTheRequestToThePractitioner() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        stripe.refuseCaptures();

        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatusOk();

        persisted();
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(paymentRow()).containsEntry("status", "AUTHORIZED");
        // requested at 09:00 UTC, default window of 12 hours: the practitioner has a real deadline
        assertThat(jdbc.queryForObject("select approval_deadline from booking where id = ?", java.sql.Timestamp.class, booking)
                .toInstant()).isEqualTo(Instant.parse("2026-11-06T21:00:00Z"));
    }

    // ***** the PaymentIntent events (guide 33, parts 6 and 7) *****

    // the normal case: our commit came first, the event finds the payment already SUCCEEDED
    @Test
    void paymentIntentSucceededAfterOurOwnCommitChangesNothing() {
        jdbc.update("update payment set status = 'SUCCEEDED', stripe_payment_intent_id = 'pi_A' where id = ?", payment);
        jdbc.update("update booking set status = 'CONFIRMED', approval_deadline = '2026-11-06T22:00+01:00' where id = ?", booking);

        assertThat(post("alpha", paymentIntentEvent("evt_1", "payment_intent.succeeded"), ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "SUCCEEDED").containsEntry("version", 0L);
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
        assertThat(stripe.captures()).isEmpty();
    }

    // a hold Stripe released by itself after 7 days, or a release of ours that Stripe did not answer
    @Test
    void paymentIntentCanceledClosesAPaymentStillHeld() {
        jdbc.update("update payment set status = 'AUTHORIZED', stripe_payment_intent_id = 'pi_A' where id = ?", payment);
        jdbc.update("update booking set status = 'REJECTED' where id = ?", booking);

        assertThat(post("alpha", paymentIntentEvent("evt_1", "payment_intent.canceled"), ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "CANCELED");
    }

    @Test
    void aPaymentIntentThatIsNotOursIsAnsweredAndIgnored() {
        assertThat(post("alpha", paymentIntentEvent("evt_1", "payment_intent.succeeded"), ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
    }

    // ***** "at least once" (guide 32, part 5) *****

    @Test
    void theSameEventTwiceHasOneEffect() {
        String event = StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A");

        assertThat(post("alpha", event, ALPHA_SECRET)).hasStatusOk();
        assertThat(post("alpha", event, ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(jdbc.queryForObject("select count(*) from stripe_event", Long.class)).isEqualTo(1);
        assertThat(paymentRow()).containsEntry("status", "AUTHORIZED").containsEntry("version", 1L);
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(stripe.cancels()).isEmpty();
    }

    // the dangerous one (guide 32, 5.2): if the second event reached the booking, already PENDING_APPROVAL, it
    // would answer NotWanted, and a VALID hold would be released
    @Test
    void twoDifferentEventsForTheSameSessionHaveOneEffectAndReleaseNothing() {
        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET)).hasStatusOk();
        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_2", "cs_test_A", "pi_A"), ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "AUTHORIZED");
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(stripe.cancels()).isEmpty();
    }

    // ***** late payment (D-031, D-046, guide 32, part 7) *****

    @Test
    void aPaymentForAnExpiredBookingIsReleasedOnItsAccount() {
        jdbc.update("update booking set status = 'EXPIRED' where id = ?", booking);

        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatusOk();

        assertThat(stripe.cancels()).containsExactly(new FakeStripeGateway.Cancel("alpha", "pi_A", "cancel-pi_A"));
        persisted();
        assertThat(paymentRow()).containsEntry("status", "CANCELED").containsEntry("stripe_payment_intent_id", "pi_A");
        assertThat(bookingStatus()).isEqualTo("EXPIRED");
    }

    // D-057: the release goes to the account of the PAYMENT, even after the practitioner moved
    @Test
    void aLatePaymentIsReleasedOnTheAccountItWasMadeOnEvenAfterAReassignment() {
        jdbc.update("update booking set status = 'EXPIRED' where id = ?", booking);
        jdbc.update("update practitioner_payment_account set account = 'beta' where practitioner_id = ?", anna);

        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatusOk();

        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::account).containsExactly("alpha");
    }

    @Test
    void anExpiredSessionExpiresThePaymentAndLeavesTheBookingToTheJob() {
        assertThat(post("alpha", StripeEvents.sessionExpired("evt_1", "cs_test_A"), ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "EXPIRED");
        assertThat(bookingStatus()).isEqualTo("PENDING_PAYMENT");
    }

    // ***** the signature (guide 32, part 3) *****

    @Test
    void aSignatureMadeWithAnotherSecretIsRefused() {
        assertRefusedAndNothingChanged(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), BETA_SECRET));
    }

    @Test
    void aBodyChangedAfterTheSignatureIsRefused() {
        String signed = StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A");
        String header = StripeEvents.signature(signed, ALPHA_SECRET, NOW);
        String changed = signed.replace("pi_A", "pi_B");

        assertRefusedAndNothingChanged(mvc.post().uri("/api/webhooks/stripe/alpha").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", header).content(changed).exchange());
    }

    // a replay: a real signature, but older than the 5 minutes of tolerance
    @Test
    void aSignatureOlderThanFiveMinutesIsRefused() {
        String event = StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A");

        assertRefusedAndNothingChanged(mvc.post().uri("/api/webhooks/stripe/alpha").contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeEvents.signature(event, ALPHA_SECRET, NOW.minus(Duration.ofMinutes(6))))
                .content(event).exchange());
    }

    @Test
    void aMissingSignatureIsRefused() {
        assertRefusedAndNothingChanged(mvc.post().uri("/api/webhooks/stripe/alpha").contentType(MediaType.APPLICATION_JSON)
                .content(StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A")).exchange());
    }

    @Test
    void anUnknownAccountIsNotFound() {
        assertThat(post("rossi", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), ALPHA_SECRET))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** what is not ours *****

    // guide 32, part 4: the endpoint of beta, a payment of alpha: a misconfiguration, never an effect
    @Test
    void anEventOfOneAccountAboutAPaymentOfTheOtherIsIgnored() {
        assertThat(post("beta", StripeEvents.sessionCompleted("evt_1", "cs_test_A", "pi_A"), BETA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
        assertThat(bookingStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aSessionThatIsNotOursIsAnsweredAndIgnored() {
        assertThat(post("alpha", StripeEvents.sessionCompleted("evt_1", "cs_test_somebody_else", "pi_X"), ALPHA_SECRET))
                .hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
    }

    // defensive: what counts is "complete" with a PaymentIntent, never payment_status (guide 32, part 2)
    @Test
    void aCompletedEventWithoutAHeldPaymentHoldsNothing() {
        String notComplete = StripeEvents.event("evt_1", "checkout.session.completed", """
                {"id": "cs_test_A", "object": "checkout.session", "status": "open", "payment_intent": "pi_A"}
                """);
        String withoutIntent = StripeEvents.event("evt_2", "checkout.session.completed", """
                {"id": "cs_test_A", "object": "checkout.session", "status": "complete", "payment_intent": null}
                """);

        assertThat(post("alpha", notComplete, ALPHA_SECRET)).hasStatusOk();
        assertThat(post("alpha", withoutIntent, ALPHA_SECRET)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
        assertThat(bookingStatus()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void anEventOfATypeWeDoNotUseIsAnsweredAndNotRecorded() {
        String event = StripeEvents.event("evt_1", "payment_intent.created", """
                {"id": "pi_A", "object": "payment_intent"}
                """);

        assertThat(post("alpha", event, ALPHA_SECRET)).hasStatusOk();

        assertThat(jdbc.queryForObject("select count(*) from stripe_event", Long.class)).isZero();
    }

    private static String paymentIntentEvent(String eventId, String type) {
        return StripeEvents.event(eventId, type, """
                {"id": "pi_A", "object": "payment_intent", "amount": 3000}
                """);
    }

    private MvcTestResult post(String account, String body, String secret) {
        return mvc.post().uri("/api/webhooks/stripe/" + account)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeEvents.signature(body, secret, NOW))
                .content(body)
                .exchange();
    }

    private void assertRefusedAndNothingChanged(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
        assertThat(bookingStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from stripe_event", Long.class)).isZero();
    }

    // what the commit would do: the changes of the request written, so that plain SQL sees them
    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> paymentRow() {
        return jdbc.queryForMap("select status, stripe_payment_intent_id, version from payment where id = ?", payment);
    }

    private String bookingStatus() {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, booking);
    }
}
