package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// A payment link paid (D-059, D-065, guide 34, parts 6 and 7): captured at once if the session still waits to be
// paid in the studio, released otherwise. "The first wins": of two links, the first payment that finds the session
// to be paid in the studio is captured, every later one is released. The states are written with SQL, as Nicholas
// asked: at step 9 a confirmed session cannot be cancelled yet (that test with the real path is a "done" criterion
// of step 11)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class PaymentLinkWebhookTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    EntityManager entityManager;

    private PaymentTestData data;
    private long booking;

    @BeforeEach
    void annaSentALinkForASessionToBePaidInTheStudio() {
        stripe.reset();
        data = new PaymentTestData(jdbc);
        long anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        booking = data.booking(anna);
    }

    @Test
    void aLinkPaidWhileTheSessionWaitsIsCapturedAndTheSessionIsPaidByCard() {
        data.linkPayment(booking, "alpha", "PENDING", "cs_L1");

        assertThat(paid("evt_1", "cs_L1", "pi_L1")).isEqualTo(200);

        assertThat(stripe.captures()).extracting(FakeStripeGateway.Capture::paymentIntentId).containsExactly("pi_L1");
        persisted();
        assertThat(paymentOf("cs_L1")).containsEntry("status", "SUCCEEDED");
        assertThat(session()).containsEntry("status", "CONFIRMED").containsEntry("paid_with", "CARD");
    }

    // the criterion of the brief: the session is no longer valid (written in the database) → the hold is released
    @Test
    void aLinkPaidForASessionNoLongerValidIsReleased() {
        data.linkPayment(booking, "alpha", "PENDING", "cs_L1");
        jdbc.update("update booking set status = 'WITHDRAWN' where id = ?", booking);

        assertThat(paid("evt_1", "cs_L1", "pi_L1")).isEqualTo(200);

        assertThat(stripe.captures()).isEmpty();
        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId).containsExactly("pi_L1");
        persisted();
        assertThat(paymentOf("cs_L1")).containsEntry("status", "CANCELED");
        assertThat(session()).containsEntry("paid_with", "IN_STUDIO");
    }

    // ***** two links (the tests asked by Nicholas, guide 34, part 7) *****

    // the old link, invalidated by the new one, is paid anyway and arrives first: it wins, the new one is released
    @Test
    void theInvalidatedLinkPaidFirstWinsAndTheNewOneIsReleased() {
        data.linkPayment(booking, "alpha", "EXPIRED", "cs_OLD");
        data.linkPayment(booking, "alpha", "PENDING", "cs_NEW");

        assertThat(paid("evt_1", "cs_OLD", "pi_OLD")).isEqualTo(200);
        assertThat(paid("evt_2", "cs_NEW", "pi_NEW")).isEqualTo(200);

        assertThat(stripe.captures()).extracting(FakeStripeGateway.Capture::paymentIntentId).containsExactly("pi_OLD");
        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId).containsExactly("pi_NEW");
        persisted();
        assertThat(paymentOf("cs_OLD")).containsEntry("status", "SUCCEEDED");
        assertThat(paymentOf("cs_NEW")).containsEntry("status", "CANCELED");
        assertThat(session()).containsEntry("paid_with", "CARD");
    }

    // the same two links, the other way round: the valid one arrives first and wins
    @Test
    void theValidLinkPaidFirstWinsAndTheInvalidatedOneIsReleased() {
        data.linkPayment(booking, "alpha", "EXPIRED", "cs_OLD");
        data.linkPayment(booking, "alpha", "PENDING", "cs_NEW");

        assertThat(paid("evt_1", "cs_NEW", "pi_NEW")).isEqualTo(200);
        assertThat(paid("evt_2", "cs_OLD", "pi_OLD")).isEqualTo(200);

        assertThat(stripe.captures()).extracting(FakeStripeGateway.Capture::paymentIntentId).containsExactly("pi_NEW");
        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId).containsExactly("pi_OLD");
        persisted();
        assertThat(session()).containsEntry("paid_with", "CARD");
    }

    // a session already paid by card (by a link before) does not want a second payment
    @Test
    void aSecondPaymentOfASessionAlreadyPaidIsReleased() {
        jdbc.update("update booking set paid_with = 'CARD' where id = ?", booking);
        data.linkPayment(booking, "alpha", "PENDING", "cs_L1");

        assertThat(paid("evt_1", "cs_L1", "pi_L1")).isEqualTo(200);

        assertThat(stripe.cancels()).extracting(FakeStripeGateway.Cancel::paymentIntentId).containsExactly("pi_L1");
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

    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> paymentOf(String sessionId) {
        return jdbc.queryForMap("select status from payment where stripe_checkout_session_id = ?", sessionId);
    }

    private Map<String, Object> session() {
        return jdbc.queryForMap("select status, paid_with from booking where id = ?", booking);
    }
}
