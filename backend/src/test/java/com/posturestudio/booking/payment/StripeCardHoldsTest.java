package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.payment.FakeStripeGateway.Cancel;
import com.posturestudio.booking.payment.FakeStripeGateway.Capture;

import jakarta.persistence.EntityManager;

// The real CardHolds (StripeCardHolds, guide 33) from the outside: approve, reject, withdraw and the expiry of
// the answer, with the fake Stripe. And the test of D-057 asked by the brief: moving a practitioner to another
// account does not move the capture or the release of a payment already made.
// Today is Friday 6 November 2026, 10:00 in Rome; the request waits until 22:00
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class StripeCardHoldsTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    BookingService bookings;

    @Autowired
    EntityManager entityManager;

    private long anna;
    private long booking;
    private long customer;
    private String paymentIntent;

    @BeforeEach
    void aRequestOfAnnaWaitsWithItsAmountHeldOnAlpha() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        booking = data.waitingBooking(anna, "cliente@example.com", "alpha");
        customer = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
        paymentIntent = "pi_" + booking;
    }

    // ***** capture *****

    @Test
    void anApprovalCapturesTheHeldAmountAndConfirms() {
        assertThat(approve()).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");

        assertThat(stripe.captures()).containsExactly(new Capture("alpha", paymentIntent, "capture-" + paymentIntent));
        persisted();
        assertThat(payment()).containsEntry("status", "SUCCEEDED");
        assertThat(bookingStatus()).isEqualTo("CONFIRMED");
    }

    // D-057, the test of the brief: the capture goes to the account the payment was made on
    @Test
    void aPractitionerMovedToAnotherAccountIsStillCapturedOnTheAccountOfThePayment() {
        reassignAnnaToBeta();

        assertThat(approve()).hasStatusOk();

        assertThat(stripe.captures()).extracting(Capture::account).containsExactly("alpha");
    }

    // guide 33, parts 4 and 5: a refusal for good is an answer; the request stays waiting, nothing changes
    @Test
    void aRefusedCaptureIs409AndTheRequestKeepsWaiting() {
        stripe.refuseCaptures();

        MvcTestResult result = approve();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CARD_CAPTURE_FAILED");
        persisted();
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(payment()).containsEntry("status", "AUTHORIZED");
    }

    @Test
    void whenStripeDoesNotAnswerTheApprovalIs503AndNothingChanges() {
        stripe.goDown();

        MvcTestResult result = approve();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        persisted();
        assertThat(bookingStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(payment()).containsEntry("status", "AUTHORIZED");
    }

    // ***** release *****

    @Test
    void aRejectionReleasesTheHold() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk();

        assertThat(stripe.cancels()).containsExactly(new Cancel("alpha", paymentIntent, "cancel-" + paymentIntent));
        persisted();
        assertThat(payment()).containsEntry("status", "CANCELED");
    }

    // D-057, the other half of the test of the brief: the release goes to the account of the payment too
    @Test
    void aPractitionerMovedToAnotherAccountIsStillReleasedOnTheAccountOfThePayment() {
        reassignAnnaToBeta();

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk();

        assertThat(stripe.cancels()).extracting(Cancel::account).containsExactly("alpha");
    }

    @Test
    void aWithdrawalReleasesTheHold() {
        assertThat(mvc.post().uri("/api/customer/bookings/" + booking + "/withdraw").with(sessions.customer(customer)))
                .hasStatusOk();

        assertThat(stripe.cancels()).extracting(Cancel::paymentIntentId).containsExactly(paymentIntent);
    }

    @Test
    void anUnansweredRequestReleasesTheHoldWhenItExpires() {
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:59+01:00' where id = ?", booking);

        bookings.expireApproval(booking);

        assertThat(stripe.cancels()).extracting(Cancel::paymentIntentId).containsExactly(paymentIntent);
        persisted();
        assertThat(bookingStatus()).isEqualTo("APPROVAL_EXPIRED");
    }

    // guide 33, part 7: the rejection must not depend on Stripe; the hold dissolves by itself, and
    // payment_intent.canceled will close the payment later
    @Test
    void aRejectionGoesThroughEvenIfStripeDoesNotAnswer() {
        stripe.goDown();

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("REJECTED");

        persisted();
        assertThat(bookingStatus()).isEqualTo("REJECTED");
        assertThat(payment()).containsEntry("status", "AUTHORIZED");
    }

    private MvcTestResult approve() {
        return mvc.post().uri("/api/panel/bookings/" + booking + "/approve").with(sessions.practitioner(anna)).exchange();
    }

    private void reassignAnnaToBeta() {
        jdbc.update("update practitioner_payment_account set account = 'beta' where practitioner_id = ?", anna);
    }

    // what the commit would do: the changes of the request written, so that plain SQL sees them
    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> payment() {
        return jdbc.queryForMap("select status, account from payment where booking_id = ?", booking);
    }

    private String bookingStatus() {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, booking);
    }
}
