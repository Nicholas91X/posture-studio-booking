package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.posturestudio.booking.booking.CardHolds;
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

    // a release asked: on which account, which PaymentIntent, with which key
    public record Cancel(String account, String paymentIntentId, String idempotencyKey) {
    }

    // a capture asked
    public record Capture(String account, String paymentIntentId, String idempotencyKey) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Cancel> cancels = new CopyOnWriteArrayList<>();
    private final List<Capture> captures = new CopyOnWriteArrayList<>();
    // the PaymentIntents captured so far, never forgotten by reset(): on Stripe a capture stays a capture
    private final java.util.Set<String> captured = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile boolean down;
    // the hold is gone: every capture is refused for good
    volatile boolean refuseCaptures;
    // "Stripe captured, our commit failed" (guide 33, part 6): the capture happens, then OUR side fails
    volatile boolean failAfterCapture;

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

    @Override
    public CardHolds.CaptureResult capturePaymentIntent(PaymentAccount account, String paymentIntentId,
                                                        String idempotencyKey) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        if (refuseCaptures) {
            return new CardHolds.CaptureResult.Refused("payment_intent_unexpected_state");
        }
        captures.add(new Capture(account.name(), paymentIntentId, idempotencyKey));
        captured.add(paymentIntentId);
        if (failAfterCapture) {
            throw new IllegalStateException("simulated: the money is captured, then our transaction fails");
        }
        return new CardHolds.CaptureResult.Captured();
    }

    @Override
    public boolean cancelPaymentIntent(PaymentAccount account, String paymentIntentId, String idempotencyKey) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        // as Stripe: a captured PaymentIntent can no longer be canceled. Remembered across reset(), as on Stripe
        if (captured.contains(paymentIntentId)) {
            return false;
        }
        cancels.add(new Cancel(account.name(), paymentIntentId, idempotencyKey));
        return true;
    }

    public void reset() {
        calls.clear();
        cancels.clear();
        captures.clear();
        down = false;
        refuseCaptures = false;
        failAfterCapture = false;
    }

    public void refuseCaptures() {
        refuseCaptures = true;
    }

    public void failAfterCapture() {
        failAfterCapture = true;
    }

    public List<Capture> captures() {
        return List.copyOf(captures);
    }

    public List<Cancel> cancels() {
        return List.copyOf(cancels);
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
