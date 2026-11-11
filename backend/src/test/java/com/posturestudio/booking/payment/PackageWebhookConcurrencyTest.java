package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.transaction.PlatformTransactionManager;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.LedgerCleanup;
import com.posturestudio.booking.TestcontainersConfiguration;

// A package paid, its notice delivered twice at the SAME moment (guide 37, part 7, the brief: "a double notice
// must not give a package away"). Real commits, in parallel: not @Transactional. What was committed is deleted
// after each test, the ledger with LedgerCleanup (the database refuses to delete it otherwise, D-069)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class PackageWebhookConcurrencyTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    FakeStripeGateway stripe;

    private long anna;
    private long offer;
    private long cliente;
    private long payment;

    @BeforeEach
    void aCustomerIsPayingAPackage() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("pkg-anna@example.com");
        data.assign(anna, "alpha");
        offer = data.packageOffer(anna);
        cliente = data.customer("pkg-cliente@example.com");
        payment = data.packagePayment(cliente, offer, "PENDING", "cs_pkg_together", "2026-11-06T08:30:00Z");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        LedgerCleanup.delete(transactionManager, jdbc, sql -> {
            sql.update("delete from credit_movement where credit_lot_id in (select id from credit_lot where payment_id = ?)",
                    payment);
            sql.update("delete from credit_lot where payment_id = ?", payment);
            sql.update("delete from stripe_event where event_id like 'evt_pkg_%'");
            sql.update("delete from payment where id = ?", payment);
            sql.update("delete from package_offer where id = ?", offer);
            sql.update("delete from service where practitioner_id = ?", anna);
            sql.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
            sql.update("delete from practitioner where id = ?", anna);
            sql.update("delete from customer where id = ?", cliente);
            sql.update("delete from app_user where email like 'pkg-%@example.com'");
        });
    }

    // stopped by stripe_event: the second insert waits for the first commit on the unique index, then inserts
    // nothing, and the event is "already processed"
    @Test
    void theSameEventDeliveredTwiceTogetherGivesOneLot() throws Exception {
        String event = paid("evt_pkg_1");

        assertThat(together(event, event)).containsOnly(200);

        assertOneLotOfFive();
    }

    // stopped by the payment: the second waits for its lock, then reads SUCCEEDED and stops
    @Test
    void twoEventsForTheSameSessionTogetherGiveOneLot() throws Exception {
        assertThat(together(paid("evt_pkg_1"), paid("evt_pkg_2"))).containsOnly(200);

        assertOneLotOfFive();
    }

    private void assertOneLotOfFive() {
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where payment_id = ?", Long.class, payment))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select sum(m.amount) from credit_movement m join credit_lot l on l.id = m.credit_lot_id
                where l.payment_id = ?
                """, Long.class, payment)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, payment))
                .isEqualTo("SUCCEEDED");
    }

    private static String paid(String eventId) {
        return StripeEvents.packageSessionCompleted(eventId, "cs_pkg_together", "pi_pkg_together", "paid",
                FixedClockConfiguration.NOW);
    }

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
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
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
}
