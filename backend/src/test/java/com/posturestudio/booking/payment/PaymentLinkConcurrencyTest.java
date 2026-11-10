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

// Two links of the same session paid at the SAME moment (guide 34, part 7): two different payments, so their own
// locks do not stop each other; the lock on the session does. Real commits, in parallel: not @Transactional.
// What was committed is deleted after each test
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public class PaymentLinkConcurrencyTest {

    private static final String ALPHA_SECRET = "whsec_alpha_not_a_real_secret";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private long anna;
    private long booking;

    @BeforeEach
    void twoLinksOfOneSession() {
        stripe.reset();
        PaymentTestData data = new PaymentTestData(jdbc);
        anna = data.practitioner("links-anna@example.com");
        data.assign(anna, "alpha");
        booking = data.booking(anna);
        data.linkPayment(booking, "alpha", "EXPIRED", "cs_links_old");
        data.linkPayment(booking, "alpha", "PENDING", "cs_links_new");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        stripe.reset();
        jdbc.update("delete from stripe_event where event_id like 'evt_links_%'");
        jdbc.update("delete from payment where booking_id = ?", booking);
        Long customer = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
        jdbc.update("delete from booking where id = ?", booking);
        Long customerUser = jdbc.queryForObject("select user_id from customer where id = ?", Long.class, customer);
        jdbc.update("delete from customer where id = ?", customer);
        jdbc.update("delete from app_user where id = ?", customerUser);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from app_user where email = 'links-anna@example.com'");
    }

    @Test
    void twoLinksPaidTogetherCaptureOneAndReleaseTheOther() throws Exception {
        List<Integer> statuses = together(
                StripeEvents.sessionCompleted("evt_links_1", "cs_links_old", "pi_links_old"),
                StripeEvents.sessionCompleted("evt_links_2", "cs_links_new", "pi_links_new"));

        assertThat(statuses).containsOnly(200);
        assertThat(stripe.captures()).hasSize(1);
        assertThat(stripe.cancels()).hasSize(1);
        // the one captured and the one released are not the same
        assertThat(stripe.captures().getFirst().paymentIntentId()).isNotEqualTo(stripe.cancels().getFirst().paymentIntentId());
        assertThat(jdbc.queryForObject("select paid_with from booking where id = ?", String.class, booking)).isEqualTo("CARD");
        assertThat(jdbc.queryForList("select status from payment where booking_id = ? order by status", String.class, booking))
                .containsExactly("CANCELED", "SUCCEEDED");
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
}
