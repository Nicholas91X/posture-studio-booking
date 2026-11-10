package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
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

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.payment.FakeStripeGateway;
import com.posturestudio.booking.payment.FakeStripeGateway.Call;

// The request of a customer up to the Stripe page (guide 31), from outside, with the real StripeCardCheckout
// and the fake Stripe. Today is Friday 6 November 2026, 10:00 in Rome. Same configuration as
// BookingCustomerApiTest: same context, same container
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class BookingCheckoutApiTest {

    private static final String MONDAY_9 = "2026-11-09T09:00:00+01:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaWorksMondayToFridayOnAccountAlpha() {
        stripe.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("cliente@example.com");
    }

    @Test
    void aRequestCreatesTheStripePageAndAPendingPaymentOnTheAccountOfThePractitioner() throws Exception {
        MvcTestResult result = request();

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long booking = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(stripe.calls()).hasSize(1);
        Call call = stripe.calls().getFirst();
        // the response carries the page Stripe gave
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isEqualTo(call.returnedUrl().toString());

        assertThat(call.account()).isEqualTo("alpha");
        assertThat(call.bookingId()).isEqualTo(booking);
        // the price copied in the booking (D-011), the name of the service, the email for the receipt
        assertThat(call.amountCents()).isEqualTo(3000);
        assertThat(call.description()).isEqualTo("Seduta");
        assertThat(call.customerEmail()).isEqualTo("cliente@example.com");
        // the page expires with the hold, 32 minutes after the request (D-061)
        assertThat(call.expiresAt()).isEqualTo(Instant.parse("2026-11-06T09:32:00Z"));
        assertThat(call.idempotencyKey()).isEqualTo("booking-" + booking + "-checkout");
        assertThat(call.successUrl()).isEqualTo(URI.create("http://localhost:3000/prenotazioni/" + booking + "?pagamento=completato"));
        assertThat(call.cancelUrl()).isEqualTo(URI.create("http://localhost:3000/prenotazioni/" + booking + "?pagamento=annullato"));

        Map<String, Object> payment = jdbc.queryForMap(
                "select account, amount_cents, status, stripe_checkout_session_id, checkout_url from payment where booking_id = ?",
                booking);
        assertThat(payment).containsEntry("account", "alpha")
                .containsEntry("amount_cents", 3000L)
                .containsEntry("status", "PENDING")
                .containsEntry("checkout_url", call.returnedUrl().toString());
        assertThat((String) payment.get("stripe_checkout_session_id")).startsWith("cs_test_fake_");
    }

    // D-057: the account is the one assigned when the payment is created
    @Test
    void thePaymentGoesToTheAccountAssignedNow() {
        jdbc.update("update practitioner_payment_account set account = 'beta' where practitioner_id = ?", anna);

        assertThat(request()).hasStatus(HttpStatus.CREATED);

        assertThat(stripe.calls().getFirst().account()).isEqualTo("beta");
        assertThat(jdbc.queryForObject("select account from payment", String.class)).isEqualTo("beta");
    }

    // guide 31, part 9: a lost response, a closed tab: the customer finds the page again
    @Test
    void theCustomerFindsThePageAgainWhileTheSlotIsHeld() throws Exception {
        MvcTestResult result = request();
        int booking = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        String page = JsonPath.read(result.getResponse().getContentAsString(), "$.paymentUrl");

        assertThat(mvc.get().uri("/api/customer/bookings/" + booking).with(sessions.customer(cliente)))
                .hasStatusOk().bodyJson().extractingPath("$.paymentUrl").isEqualTo(page);
        assertThat(mvc.get().uri("/api/customer/bookings").with(sessions.customer(cliente)))
                .hasStatusOk().bodyJson().extractingPath("$[0].paymentUrl").isEqualTo(page);
        assertThat(stripe.calls()).hasSize(1);
    }

    // Written with SQL, not through the API: a booking created by the request would stay in the persistence
    // context of this test's transaction, and an update by SQL would not be seen when it is read again
    @Test
    void anExpiredHoldShowsNoPageAnyMore() {
        // the hold ended a minute ago (the clock of this test is fixed at 10:00 in Rome)
        long booking = data.booking(anna, seduta, cliente, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00",
                "PENDING_PAYMENT", "2026-11-06T09:59+01:00");
        jdbc.update("""
                insert into payment (booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url)
                values (?, 'alpha', 3000, 'PENDING', 'cs_test_old', 'https://checkout.stripe.test/c/pay/cs_test_old')
                """, booking);

        assertThat(mvc.get().uri("/api/customer/bookings/" + booking).with(sessions.customer(cliente)))
                .hasStatusOk().bodyJson().extractingPath("$.paymentUrl").isNull();
    }

    @Test
    void aBookingFromThePanelHasNoPage() {
        long booking = data.panelBooking(anna, seduta, cliente, "2026-11-10T09:00+01:00", "2026-11-10T10:00+01:00");

        assertThat(mvc.get().uri("/api/customer/bookings/" + booking).with(sessions.customer(cliente)))
                .hasStatusOk().bodyJson().extractingPath("$.paymentUrl").isNull();
    }

    // guide 30, part 6: refused before anything is written, so no slot is held for nobody
    @Test
    void aPractitionerWithoutAccountRefusesTheRequestAndHoldsNothing() {
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);

        MvcTestResult result = request();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CARD_PAYMENTS_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from booking", Long.class)).isZero();
        assertThat(stripe.calls()).isEmpty();
    }

    // Stripe down: BookingCheckoutRollbackTest, which needs real commits and rollbacks

    private MvcTestResult request() {
        return mvc.post().uri("/api/customer/bookings").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d, "startsAt": "%s"}
                        """.formatted(anna, seduta, MONDAY_9))
                .exchange();
    }
}
