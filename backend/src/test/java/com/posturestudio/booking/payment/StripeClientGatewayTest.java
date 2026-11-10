package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.ServiceUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// The REAL gateway, with the real stripe-java, against a fake Stripe on localhost (guide 31, part 10): what
// the library actually sends. Same idea as the fake push service of guide 28. No Spring
class StripeClientGatewayTest {

    private static final PaymentAccounts ACCOUNTS = PaymentAccounts.from("alpha,beta", false, Map.of(
            "STRIPE_ALPHA_SECRET_KEY", "rk_test_alpha_key",
            "STRIPE_ALPHA_WEBHOOK_SECRET", "whsec_alpha",
            "STRIPE_BETA_SECRET_KEY", "rk_test_beta_key",
            "STRIPE_BETA_WEBHOOK_SECRET", "whsec_beta")::get);

    private static final StripeGateway.NewCheckoutSession SESSION = new StripeGateway.NewCheckoutSession(
            42, 3000, "Seduta", "cliente@example.com", Instant.parse("2026-11-06T09:32:00.900Z"),
            URI.create("http://localhost:3000/prenotazioni/42?pagamento=completato"),
            URI.create("http://localhost:3000/prenotazioni/42?pagamento=annullato"));

    // what the fake Stripe received
    record Received(String method, String path, String authorization, String idempotencyKey, Map<String, String> form) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = """
            {"id": "cs_test_123", "object": "checkout.session", "url": "https://checkout.stripe.com/c/pay/cs_test_123"}
            """;
    private volatile boolean shouldRetry;
    // when set, the fake Stripe takes the request and does not answer until released (or 30 s)
    private volatile CountDownLatch hang;

    private HttpServer server;
    private StripeClientGateway gateway;

    @BeforeEach
    void startTheFakeStripe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        // one thread per request, daemons: a request left hanging does not hold the next one, nor the JVM
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        gateway = new StripeClientGateway(ACCOUNTS, "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (hang != null) {
            hang.countDown();
        }
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"), form(body)));
        CountDownLatch latch = hang;
        if (latch != null) {
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Request-Id", "req_fake_1");
        if (shouldRetry) {
            exchange.getResponseHeaders().add("Stripe-Should-Retry", "true");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&"))
                .filter(pair -> !pair.isEmpty())
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }

    @Test
    void theSessionHoldsTheAmountOfTheBookingAndExpiresWithTheHold() {
        StripeGateway.CheckoutSession session = gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(),
                SESSION, "booking-42-checkout");

        assertThat(session).isEqualTo(new StripeGateway.CheckoutSession("cs_test_123",
                URI.create("https://checkout.stripe.com/c/pay/cs_test_123")));
        Received request = received.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/checkout/sessions");
        assertThat(request.idempotencyKey()).isEqualTo("booking-42-checkout");
        assertThat(request.form())
                .containsEntry("mode", "payment")
                // held, not captured (D-031)
                .containsEntry("payment_intent_data[capture_method]", "manual")
                .containsEntry("payment_intent_data[metadata][booking_id]", "42")
                .containsEntry("line_items[0][quantity]", "1")
                .containsEntry("line_items[0][price_data][currency]", "eur")
                .containsEntry("line_items[0][price_data][unit_amount]", "3000")
                .containsEntry("line_items[0][price_data][product_data][name]", "Seduta")
                .containsEntry("allowed_payment_method_types[0]", "card")
                .containsEntry("metadata[booking_id]", "42")
                .containsEntry("client_reference_id", "42")
                // 09:32:00.900 rounded DOWN to the second: the page never outlives the hold (guide 31, part 5)
                .containsEntry("expires_at", Long.toString(Instant.parse("2026-11-06T09:32:00Z").getEpochSecond()))
                .containsEntry("customer_email", "cliente@example.com")
                .containsEntry("locale", "it")
                .containsEntry("submit_type", "book")
                .containsEntry("success_url", "http://localhost:3000/prenotazioni/42?pagamento=completato")
                .containsEntry("cancel_url", "http://localhost:3000/prenotazioni/42?pagamento=annullato");
        // never the name of the customer (guide 31, part 6)
        assertThat(request.form().keySet()).noneMatch(key -> key.contains("name") && !key.contains("product_data"));
    }

    // D-057: every account its own client, its own key
    @Test
    void eachAccountCallsStripeWithItsOwnKey() {
        gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(), SESSION, "key-1");
        gateway.createCheckoutSession(ACCOUNTS.byName("beta").orElseThrow(), SESSION, "key-2");

        assertThat(received).extracting(Received::authorization)
                .containsExactly("Bearer rk_test_alpha_key", "Bearer rk_test_beta_key");
    }

    @Test
    void aRefusalOfStripeBecomesPaymentProviderUnavailableWithoutRetry() {
        status = 400;
        answer = """
                {"error": {"type": "invalid_request_error", "code": "parameter_invalid_integer", "message": "Invalid expires_at"}}
                """;

        assertThatThrownBy(() -> gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(), SESSION, "key-1"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE));
        // a refusal is final: asking again would get the same answer
        assertThat(received).hasSize(1);
    }

    // guide 31, 8.1: one retry, safe because it carries the SAME idempotency key
    @Test
    void aTemporaryFailureIsRetriedOnceWithTheSameIdempotencyKey() {
        status = 503;
        shouldRetry = true;
        answer = """
                {"error": {"type": "api_error", "message": "Try again"}}
                """;

        assertThatThrownBy(() -> gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(), SESSION, "key-1"))
                .isInstanceOf(ServiceUnavailableException.class);
        // the number written out: comparing with MAX_NETWORK_RETRIES would follow any change of the constant
        assertThat(received).hasSize(2)
                .extracting(Received::idempotencyKey).containsOnly("key-1");
    }

    // D-061, guide 31, 8.1: the call holds the customer lock and the slot, so it must give up soon. With the
    // REAL values: two reads of 5 s each, one retry with the same key, the pause in between: between 10 and
    // 15 seconds. Slow on purpose: tiny timeouts set by the test would prove nothing about the real ones.
    // The connect timeout (2 s) is not tested: it needs an address that swallows connections, and on many
    // machines an unroutable address fails at once instead
    @Test
    void aStripeThatHangsIsAbandonedAfterOneRetryWithinTheWorstCase() {
        hang = new CountDownLatch(1);
        long start = System.nanoTime();

        assertThatThrownBy(() -> gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(), SESSION, "key-1"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(received).hasSize(2).extracting(Received::idempotencyKey).containsOnly("key-1");
        assertThat(elapsed).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Test
    void aStripeThatDoesNotAnswerBecomesPaymentProviderUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.createCheckoutSession(ACCOUNTS.byName("alpha").orElseThrow(), SESSION, "key-1"))
                .isInstanceOfSatisfying(ServiceUnavailableException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE));
    }
}
