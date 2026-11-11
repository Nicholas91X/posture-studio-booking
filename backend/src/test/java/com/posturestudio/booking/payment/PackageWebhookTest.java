package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// A package paid (guide 37, part 7): the webhook from outside, real signatures, the lot born once. The rows are
// written with SQL, so that no entity of the test transaction is already in memory. Today is Friday 6 November
// 2026, 10:00 in Rome; the events are created at the same instant unless a test says otherwise.
// Two deliveries at the SAME moment are in PackageWebhookConcurrencyTest
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class PackageWebhookTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";
    private static final Instant NOW = FixedClockConfiguration.NOW;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    TestSessions sessions;

    @Autowired
    EntityManager entityManager;

    private long anna;
    private long offer;
    private long cliente;
    private long payment;

    // the customer is on the Stripe page of "5 sedute", 120 euros, 90 days, opened half an hour ago
    @BeforeEach
    void aCustomerIsPayingAPackage() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        offer = data.packageOffer(anna);
        cliente = data.customer("cliente@example.com");
        payment = data.packagePayment(cliente, offer, "PENDING", "cs_test_P", "2026-11-06T08:30:00Z");
    }

    @Test
    void aPaidPackageBecomesALotWithItsCredits() {
        assertThat(post(paid("evt_1"))).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "SUCCEEDED").containsEntry("stripe_payment_intent_id", "pi_P");
        Map<String, Object> lot = lot();
        assertThat(lot)
                .containsEntry("source", "PACKAGE")
                .containsEntry("customer_id", cliente)
                .containsEntry("practitioner_id", anna)
                .containsEntry("package_offer_id", offer)
                .containsEntry("price_cents", 12000L)
                // paid on 6 November in Rome, 90 days: usable until 4 February 2027 included (D-069)
                .containsEntry("valid_until", java.sql.Date.valueOf("2027-02-04"));
        assertThat(jdbc.queryForObject("select service_id from package_offer where id = ?", Long.class, offer))
                .isEqualTo(lot.get("service_id"));
        assertThat(jdbc.queryForList("select reason || ' ' || amount from credit_movement where credit_lot_id = ?",
                String.class, lot.get("id"))).containsExactly("LOT_CREATED 5");
        // captured at once: nothing to capture or release on Stripe
        assertThat(stripe.captures()).isEmpty();
        assertThat(stripe.cancels()).isEmpty();
    }

    // guide 37, part 8: the day the customer paid, in Rome, from the event, not the day we process it
    @Test
    void theValidityCountsFromTheDayOfThePaymentInRome() {
        // 23:30 UTC on 6 November is 00:30 on Saturday 7 November in Rome
        String body = StripeEvents.packageSessionCompleted("evt_1", "cs_test_P", "pi_P", "paid",
                Instant.parse("2026-11-06T23:30:00Z"));

        assertThat(post(body)).hasStatusOk();

        persisted();
        assertThat(lot()).containsEntry("valid_until", java.sql.Date.valueOf("2027-02-05"));
    }

    // ***** once only (guide 37, part 7) *****

    // Stripe sends the same event again: stopped by stripe_event
    @Test
    void theSameEventTwiceGivesOneLot() {
        assertThat(post(paid("evt_1"))).hasStatusOk();
        assertThat(post(paid("evt_1"))).hasStatusOk();

        persisted();
        assertOneLotOfFive();
    }

    // two different events for the same session: stopped by the payment, PENDING -> SUCCEEDED once
    @Test
    void twoEventsForTheSameSessionGiveOneLot() {
        assertThat(post(paid("evt_1"))).hasStatusOk();
        assertThat(post(paid("evt_2"))).hasStatusOk();

        persisted();
        assertOneLotOfFive();
    }

    // complete but not paid must never create credits
    @Test
    void aSessionCompleteButNotPaidCreatesNothing() {
        String body = StripeEvents.packageSessionCompleted("evt_1", "cs_test_P", "pi_P", "unpaid", NOW);

        assertThat(post(body)).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "PENDING");
        assertThat(lots()).isZero();
    }

    // ***** the photo (D-069) *****

    // the practitioner changes the package while the customer pays: the customer gets what they saw
    @Test
    void aPackageChangedAfterThePageGivesWhatWasPaid() {
        jdbc.update("update package_offer set sessions_count = 3, price_cents = 9000, validity_days = 30 where id = ?", offer);

        assertThat(post(paid("evt_1"))).hasStatusOk();

        persisted();
        assertThat(lot()).containsEntry("price_cents", 12000L).containsEntry("valid_until", java.sql.Date.valueOf("2027-02-04"));
        assertThat(jdbc.queryForObject("select amount from credit_movement where credit_lot_id = ?", Integer.class,
                lot().get("id"))).isEqualTo(5);
    }

    // the brief: deactivated between the payment and the notice, the customer paid, the lot is born
    @Test
    void aPackageDeactivatedAfterThePaymentStillGivesTheLot() {
        jdbc.update("update package_offer set active = false where id = ?", offer);
        jdbc.update("update service set active = false where id = (select service_id from package_offer where id = ?)", offer);

        assertThat(post(paid("evt_1"))).hasStatusOk();

        persisted();
        assertOneLotOfFive();
    }

    // ***** the other events *****

    @Test
    void anExpiredPageExpiresThePayment() {
        assertThat(post(StripeEvents.sessionExpired("evt_1", "cs_test_P"))).hasStatusOk();

        persisted();
        assertThat(paymentRow()).containsEntry("status", "EXPIRED");
        assertThat(lots()).isZero();
    }

    // captured at once, so this arrives for every package: after the completed event, nothing to do
    @Test
    void paymentIntentSucceededAfterThePaymentChangesNothing() {
        post(paid("evt_1"));

        assertThat(post(StripeEvents.event("evt_2", "payment_intent.succeeded", """
                {"id": "pi_P", "object": "payment_intent", "status": "succeeded"}
                """))).hasStatusOk();

        persisted();
        assertOneLotOfFive();
        assertThat(paymentRow()).containsEntry("status", "SUCCEEDED");
    }

    // and before it: we do not know that PaymentIntent yet, and the completed event does everything after
    @Test
    void paymentIntentSucceededBeforeThePaymentChangesNothingAndTheLotFollows() {
        assertThat(post(StripeEvents.event("evt_0", "payment_intent.succeeded", """
                {"id": "pi_P", "object": "payment_intent", "status": "succeeded"}
                """))).hasStatusOk();
        assertThat(post(paid("evt_1"))).hasStatusOk();

        persisted();
        assertOneLotOfFive();
    }

    // ***** what the customer reads after (guide 37, part 5) *****

    @Test
    void afterThePaymentThePurchaseIsPaidWithItsLot() {
        post(paid("evt_1"));
        persisted();

        MvcTestResult result = mvc.get().uri("/api/customer/package-purchases/" + payment)
                .with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(result).bodyJson().extractingPath("$.credits").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.validUntil").isEqualTo("2027-02-04");
        assertThat(result).bodyJson().extractingPath("$.lotId").isEqualTo(((Long) lot().get("id")).intValue());
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isNull();
    }

    // ***** helpers *****

    private static String paid(String eventId) {
        return StripeEvents.packageSessionCompleted(eventId, "cs_test_P", "pi_P", "paid", NOW);
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri("/api/webhooks/stripe/alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", StripeEvents.signature(body, ALPHA_SECRET, NOW))
                .content(body)
                .exchange();
    }

    private void assertOneLotOfFive() {
        assertThat(lots()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(amount) from credit_movement where credit_lot_id = ?", Long.class,
                lot().get("id"))).isEqualTo(5);
    }

    // the webhook ran in the transaction of the test: what JPA holds goes to the database first
    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> paymentRow() {
        return jdbc.queryForMap("select status, stripe_payment_intent_id from payment where id = ?", payment);
    }

    private long lots() {
        return jdbc.queryForObject("select count(*) from credit_lot where payment_id = ?", Long.class, payment);
    }

    private Map<String, Object> lot() {
        return jdbc.queryForMap("select * from credit_lot where payment_id = ?", payment);
    }
}
