package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.ServiceUnavailableException;

/**
 * A Stripe that lives in memory (guide 31, part 10): it remembers what it was asked, invents sessions, and can
 * be "down" on demand. Registered in EVERY test context through TestcontainersConfiguration, @Primary over the
 * real StripeClientGateway: no test can reach the real Stripe by mistake.
 * The bean lives as long as its context: tests that look at it reset it first.
 */
public class FakeStripeGateway implements StripeGateway {

    // what one call asked: on which account, which session, with which idempotency key
    public record Call(String account, long bookingId, long amountCents, String description, String customerEmail,
                       Instant expiresAt, URI successUrl, URI cancelUrl, String idempotencyKey, URI returnedUrl) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile boolean down;

    @Override
    public CheckoutSession createCheckoutSession(PaymentAccount account, NewCheckoutSession session, String idempotencyKey) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        // unique even across tests that commit in the same database
        String id = "cs_test_fake_" + UUID.randomUUID().toString().replace("-", "");
        URI url = URI.create("https://checkout.stripe.test/c/pay/" + id);
        calls.add(new Call(account.name(), session.bookingId(), session.amountCents(), session.description(),
                session.customerEmail(), session.expiresAt(), session.successUrl(), session.cancelUrl(),
                idempotencyKey, url));
        return new CheckoutSession(id, url);
    }

    public void reset() {
        calls.clear();
        down = false;
    }

    public void goDown() {
        down = true;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        FakeStripeGateway fakeStripeGateway() {
            return new FakeStripeGateway();
        }
    }
}
