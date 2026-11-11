package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

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
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// A customer buys a package (guide 37, parts 2 to 5): real sessions, the real controller and service, the fake
// Stripe. Today is Friday 6 November 2026, 10:00 in Rome (09:00 UTC)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class PackagePurchaseApiTest {

    private static final Instant NOW = FixedClockConfiguration.NOW;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private PaymentTestData data;
    private long anna;
    private long offer;
    private long cliente;

    // Anna sells "5 sedute" of her service for 120 euros, valid 90 days, on the account alpha
    @BeforeEach
    void annaSellsAPackage() {
        stripe.reset();
        data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        offer = data.packageOffer(anna);
        cliente = data.customer("cliente@example.com");
    }

    // ***** the purchase *****

    @Test
    void aPurchaseOpensAStripePageForAnHour() {
        MvcTestResult result = buy(offer);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long id = paymentOf(cliente);
        assertThat(result).hasHeader("Location", "/api/customer/package-purchases/" + id);
        FakeStripeGateway.PackageCall call = stripe.packageCalls().getFirst();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING");
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isEqualTo(call.returnedUrl().toString());
        assertThat(result).bodyJson().extractingPath("$.credits").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.amountCents").isEqualTo(12000);
        assertThat(result).bodyJson().extractingPath("$.validityDays").isEqualTo(90);
        assertThat(result).bodyJson().extractingPath("$.pageExpiresAt").isEqualTo("2026-11-06T10:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.lotId").isNull();

        // what Stripe was asked: the account of Anna, the price of the package, an hour (guide 37, part 3)
        assertThat(stripe.packageCalls()).hasSize(1);
        assertThat(stripe.calls()).isEmpty();
        assertThat(call.account()).isEqualTo("alpha");
        assertThat(call.packageOfferId()).isEqualTo(offer);
        assertThat(call.amountCents()).isEqualTo(12000);
        assertThat(call.description()).isEqualTo("5 sedute · Seduta");
        assertThat(call.customerEmail()).isEqualTo("cliente@example.com");
        assertThat(call.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(call.successUrl()).isEqualTo(URI.create("http://localhost:3000/crediti?acquisto=completato"));
        assertThat(call.cancelUrl()).isEqualTo(URI.create("http://localhost:3000/crediti?acquisto=annullato"));
        assertThat(call.idempotencyKey()).startsWith("package-" + cliente + "-" + offer + "-");
    }

    // the photo of D-069: price, sessions and validity in the payment, as the customer saw them
    @Test
    void thePaymentKeepsThePhotoOfWhatWasSold() {
        buy(offer);

        assertThat(jdbc.queryForMap("""
                select purpose, status, account, amount_cents, package_offer_id, customer_id, package_credits,
                       package_validity_days, booking_id, source
                from payment where customer_id = ?
                """, cliente))
                .containsEntry("purpose", "PACKAGE")
                .containsEntry("status", "PENDING")
                .containsEntry("account", "alpha")
                .containsEntry("amount_cents", 12000L)
                .containsEntry("package_offer_id", offer)
                .containsEntry("package_credits", 5)
                .containsEntry("package_validity_days", 90)
                .containsEntry("booking_id", null)
                .containsEntry("source", "REQUEST");
    }

    // a new key at every request (guide 37, part 4): two clicks, two pages, and Stripe does not merge them
    @Test
    void twoPurchasesAreTwoPagesWithTwoKeys() {
        buy(offer);
        buy(offer);

        assertThat(stripe.packageCalls()).extracting(FakeStripeGateway.PackageCall::idempotencyKey)
                .doesNotHaveDuplicates().hasSize(2);
    }

    // ***** what is not on sale: 404, and Stripe is never asked *****

    @Test
    void aDeactivatedPackageIsNotOnSale() {
        jdbc.update("update package_offer set active = false where id = ?", offer);

        assertNotOnSale(buy(offer));
    }

    @Test
    void aPackageOfADeactivatedServiceIsNotOnSale() {
        jdbc.update("update service set active = false where id = (select service_id from package_offer where id = ?)", offer);

        assertNotOnSale(buy(offer));
    }

    @Test
    void aPackageOfADeactivatedPractitionerIsNotOnSale() {
        jdbc.update("update app_user set active = false where id = (select user_id from practitioner where id = ?)", anna);

        assertNotOnSale(buy(offer));
    }

    @Test
    void aMissingPackageIsNotFound() {
        assertNotOnSale(buy(999_999));
    }

    // ***** what stops the purchase *****

    // guide 30, part 6: the same code as a request of a session, BEFORE asking Stripe anything
    @Test
    void withoutAPaymentAccountNothingIsSold() {
        long marco = data.practitioner("marco@example.com");
        long marcosOffer = data.packageOffer(marco);

        MvcTestResult result = buy(marcosOffer);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CARD_PAYMENTS_UNAVAILABLE");
        assertThat(stripe.packageCalls()).isEmpty();
        assertThat(paymentsOf(cliente)).isZero();
    }

    // D-061: Stripe not answering leaves nothing behind
    @Test
    void stripeDownLeavesNothing() {
        stripe.goDown();

        MvcTestResult result = buy(offer);

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(paymentsOf(cliente)).isZero();
    }

    @Test
    void aPurchaseNamesItsPackage() {
        assertThat(mvc.post().uri("/api/customer/package-purchases").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyACustomerBuys() {
        assertThat(mvc.post().uri("/api/customer/package-purchases").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON).content("{\"packageOfferId\": " + offer + "}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/api/customer/package-purchases")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"packageOfferId\": " + offer + "}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stripe.packageCalls()).isEmpty();
    }

    // ***** what the customer reads (guide 37, part 5) *****

    @Test
    void myPurchasesTheLatestFirst() {
        long older = data.packagePayment(cliente, offer, "EXPIRED", "cs_test_old", "2026-11-05T09:00:00Z");
        long newer = data.packagePayment(cliente, offer, "PENDING", "cs_test_new", "2026-11-06T08:30:00Z");

        MvcTestResult result = mvc.get().uri("/api/customer/package-purchases").with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) newer, (int) older);
        assertThat(result).bodyJson().extractingPath("$[0].status").isEqualTo("PENDING");
        assertThat(result).bodyJson().extractingPath("$[0].paymentUrl").isEqualTo("https://checkout.stripe.test/c/pay/cs_test_new");
        assertThat(result).bodyJson().extractingPath("$[1].status").isEqualTo("EXPIRED");
        assertThat(result).bodyJson().extractingPath("$[1].paymentUrl").isNull();
    }

    // the notice of Stripe can be late: past the hour, the page is gone by the clock (as a booking, D-061)
    @Test
    void aPageOlderThanAnHourIsExpiredEvenWithoutTheNotice() {
        long purchase = data.packagePayment(cliente, offer, "PENDING", "cs_test_late", "2026-11-06T08:00:00Z");

        MvcTestResult result = mvc.get().uri("/api/customer/package-purchases/" + purchase)
                .with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(result).bodyJson().extractingPath("$.paymentUrl").isNull();
        assertThat(result).bodyJson().extractingPath("$.pageExpiresAt").isNull();
    }

    @Test
    void thePurchaseOfAnotherIsNotFound() {
        long other = data.customer("altro@example.com");
        long purchase = data.packagePayment(other, offer, "PENDING", "cs_test_other", "2026-11-06T08:30:00Z");

        assertThat(mvc.get().uri("/api/customer/package-purchases/" + purchase).with(sessions.customer(cliente)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/customer/package-purchases").with(sessions.customer(cliente)))
                .bodyJson().extractingPath("$").asArray().isEmpty();
    }

    // a payment of a session is not a purchase, even of the same customer
    @Test
    void aPaymentOfASessionIsNotAPurchase() {
        long booking = data.booking(anna);
        long session = data.payment(booking, "alpha", "PENDING", "cs_test_session");
        long customerOfTheBooking = jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);

        assertThat(mvc.get().uri("/api/customer/package-purchases/" + session).with(sessions.customer(customerOfTheBooking)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** helpers *****

    private MvcTestResult buy(long packageOfferId) {
        return mvc.post().uri("/api/customer/package-purchases").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageOfferId\": " + packageOfferId + "}")
                .exchange();
    }

    private void assertNotOnSale(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(stripe.packageCalls()).isEmpty();
        assertThat(paymentsOf(cliente)).isZero();
    }

    private long paymentOf(long customerId) {
        return jdbc.queryForObject("select id from payment where customer_id = ?", Long.class, customerId);
    }

    private long paymentsOf(long customerId) {
        return jdbc.queryForObject("select count(*) from payment where customer_id = ?", Long.class, customerId);
    }
}
