package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

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

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// "Send a payment link" from the panel (D-059, D-065, guide 34), with real sessions and the fake Stripe.
// Today is Friday 6 November 2026, 10:00 in Rome; the session of Anna, to be paid in the studio, is on
// Friday 20 November at 9:00
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class PaymentLinkApiTest {

    private static final Instant NOW = FixedClockConfiguration.NOW;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    @Autowired
    PaymentAccounts accounts;

    @Autowired
    EntityManager entityManager;

    private PaymentTestData data;
    private long anna;
    private long booking;

    @BeforeEach
    void annaHasASessionToBePaidInTheStudio() {
        stripe.reset();
        data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        booking = data.booking(anna);
    }

    @Test
    void aLinkIsAStripePageOnTheAccountOfThePractitionerHeldAndValidFor24Hours() throws Exception {
        MvcTestResult result = link(booking);

        assertThat(result).hasStatusOk();
        FakeStripeGateway.Call call = stripe.calls().getFirst();
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.paymentUrl"))
                .isEqualTo(call.returnedUrl().toString());
        assertThat(call.account()).isEqualTo("alpha");
        assertThat(call.amountCents()).isEqualTo(3000);
        // 24 hours less the margin of 2 minutes (Stripe wants LESS than 24 hours), the start being later
        assertThat(call.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 3600 - 120));
        assertThat(call.idempotencyKey()).startsWith("booking-" + booking + "-link-");
        assertThat(result).bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-07T09:58:00+01:00");

        persisted();
        assertThat(jdbc.queryForMap("select status, source, account from payment where booking_id = ?", booking))
                .containsEntry("status", "PENDING").containsEntry("source", "LINK").containsEntry("account", "alpha");
    }

    // guide 34, part 9: without a number the practitioner picks the contact; the text is ready
    @Test
    void theWhatsappAddressCarriesTheMessageAndNoNumberWhenTheCustomerHasNone() throws Exception {
        MvcTestResult result = link(booking);

        String whatsapp = JsonPath.read(result.getResponse().getContentAsString(), "$.whatsappUrl");
        String paymentUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.paymentUrl");
        assertThat(whatsapp).startsWith("https://wa.me/?text=");
        assertThat(textOf(whatsapp)).isEqualTo("Ciao Test, ecco il link per pagare la seduta \"Seduta\" di venerdì"
                + " 20 novembre, ore 9:00: " + paymentUrl + " Il link vale fino a sabato 7 novembre, ore 9:58.");
    }

    @Test
    void theWhatsappAddressIsTheVerifiedNumberOfTheCustomerWithoutThePlus() throws Exception {
        jdbc.update("""
                update app_user set phone = '+393331234567', phone_verified_at = now()
                where id = (select c.user_id from customer c join booking b on b.customer_id = c.id where b.id = ?)
                """, booking);

        String whatsapp = JsonPath.read(link(booking).getResponse().getContentAsString(), "$.whatsappUrl");

        assertThat(whatsapp).startsWith("https://wa.me/393331234567?text=");
    }

    // guide 34, part 4: never beyond the start of the session
    @Test
    void aLinkForASessionThatStartsSoonExpiresAtItsStart() {
        startsAt("2026-11-06T19:00:00Z");

        assertThat(link(booking)).hasStatusOk().bodyJson().extractingPath("$.expiresAt")
                .isEqualTo("2026-11-06T20:00:00+01:00");
        assertThat(stripe.calls().getFirst().expiresAt()).isEqualTo(Instant.parse("2026-11-06T19:00:00Z"));
    }

    @Test
    void aSessionStartingInLessThan32MinutesGetsNoLink() {
        startsAt("2026-11-06T09:30:00Z");

        assertThat(link(booking)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_LINK_NOT_ALLOWED");
        assertThat(stripe.calls()).isEmpty();
    }

    // ***** one valid link per session (guide 34, part 5) *****

    @Test
    void aNewLinkMakesTheOldStripePageExpire() {
        assertThat(link(booking)).hasStatusOk();
        String oldSession = stripe.calls().getFirst().returnedUrl().getPath().replace("/c/pay/", "");

        assertThat(link(booking)).hasStatusOk();

        assertThat(stripe.expiredSessions()).containsExactly(oldSession);
        persisted();
        assertThat(jdbc.queryForObject("select status from payment where stripe_checkout_session_id = ?", String.class,
                oldSession)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from payment where booking_id = ? and status = 'PENDING'",
                Long.class, booking)).isEqualTo(1);
    }

    @Test
    void anOldPageAlreadyExpiredOnStripeIsOnlyClosedHere() {
        assertThat(link(booking)).hasStatusOk();
        String oldSession = stripe.calls().getFirst().returnedUrl().getPath().replace("/c/pay/", "");
        stripe.expireCheckoutSession(accounts.byName("alpha").orElseThrow(), oldSession, "by-stripe");

        assertThat(link(booking)).hasStatusOk();

        persisted();
        assertThat(jdbc.queryForObject("select status from payment where stripe_checkout_session_id = ?", String.class,
                oldSession)).isEqualTo("EXPIRED");
    }

    // the customer paid the old link a moment ago: the money is on its way, no new link
    @Test
    void anOldLinkJustPaidRefusesANewOne() {
        assertThat(link(booking)).hasStatusOk();
        String oldSession = stripe.calls().getFirst().returnedUrl().getPath().replace("/c/pay/", "");
        stripe.customerPaid(oldSession);

        MvcTestResult result = link(booking);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_LINK_ALREADY_PAID");
        assertThat(stripe.calls()).hasSize(1);
    }

    // ***** who and what *****

    @Test
    void aSessionPaidByCardGetsNoLink() {
        long request = data.pendingBooking(anna, "carta@example.com");

        assertThat(link(request)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_LINK_NOT_ALLOWED");
    }

    @Test
    void theSessionOfAnotherPractitionerIsNotFound() {
        long marco = data.practitioner("marco@example.com");

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/payment-link").with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aPractitionerWithoutAccountGetsNoLink() {
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);

        assertThat(link(booking)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("CARD_PAYMENTS_UNAVAILABLE");
    }

    @Test
    void onlyThePractitionerSendsLinks() {
        Long customerUser = jdbc.queryForObject(
                "select c.user_id from customer c join booking b on b.customer_id = c.id where b.id = ?", Long.class, booking);

        assertThat(mvc.post().uri("/api/panel/bookings/" + booking + "/payment-link").with(sessions.user(customerUser)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult link(long bookingId) {
        return mvc.post().uri("/api/panel/bookings/" + bookingId + "/payment-link").with(sessions.practitioner(anna)).exchange();
    }

    private void startsAt(String start) {
        Instant startsAt = Instant.parse(start);
        jdbc.update("update booking set starts_at = ?, ends_at = ? where id = ?", java.sql.Timestamp.from(startsAt),
                java.sql.Timestamp.from(startsAt.plusSeconds(3600)), booking);
    }

    private static String textOf(String whatsapp) {
        return URLDecoder.decode(URI.create(whatsapp).getRawQuery().substring("text=".length()), StandardCharsets.UTF_8);
    }

    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }
}
