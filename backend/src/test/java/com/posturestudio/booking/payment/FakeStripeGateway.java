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
    // the PaymentIntents captured so far: on Stripe a capture stays a capture (forgetCalls() keeps them)
    private final java.util.Set<String> captured = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // and the ones released: on Stripe a cancel stays a cancel
    private final java.util.Set<String> canceled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    volatile boolean down;
    // the hold is gone: every capture is refused for good
    volatile boolean refuseCaptures;
    // "Stripe captured, our commit failed" (guide 33, part 6): the capture happens, then OUR side fails
    volatile boolean failAfterCapture;
    // a 500 of Stripe on the next capture: nothing captured, and the caller gets ServiceUnavailableException
    volatile boolean failNextCapture;
    // the capture happens on Stripe, but its response is lost: the caller gets ServiceUnavailableException
    volatile boolean loseNextCaptureResponse;
    // every capture asked, with its key, also the failed ones
    private final List<String> captureKeys = new CopyOnWriteArrayList<>();
    // Checkout sessions the customer paid, and the ones made to expire (all the others are open)
    private final java.util.Set<String> paidSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<String> expiredSessions = new CopyOnWriteArrayList<>();

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
    public CheckoutSessionStatus checkoutSessionStatus(PaymentAccount account, String checkoutSessionId) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        if (paidSessions.contains(checkoutSessionId)) {
            return CheckoutSessionStatus.COMPLETE;
        }
        return expiredSessions.contains(checkoutSessionId) ? CheckoutSessionStatus.EXPIRED : CheckoutSessionStatus.OPEN;
    }

    // as Stripe: only an open session can expire
    @Override
    public boolean expireCheckoutSession(PaymentAccount account, String checkoutSessionId, String idempotencyKey) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        if (checkoutSessionStatus(account, checkoutSessionId) != CheckoutSessionStatus.OPEN) {
            return false;
        }
        expiredSessions.add(checkoutSessionId);
        return true;
    }

    // the customer paid this session on Stripe's page (the webhook is sent by the test)
    public void customerPaid(String checkoutSessionId) {
        paidSessions.add(checkoutSessionId);
    }

    public List<String> expiredSessions() {
        return List.copyOf(expiredSessions);
    }

    // as Stripe: captured stays captured, released stays released, otherwise still held
    @Override
    public PaymentIntentStatus paymentIntentStatus(PaymentAccount account, String paymentIntentId) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        if (captured.contains(paymentIntentId)) {
            return PaymentIntentStatus.SUCCEEDED;
        }
        if (refuseCaptures || canceled.contains(paymentIntentId)) {
            return PaymentIntentStatus.CANCELED;
        }
        return PaymentIntentStatus.REQUIRES_CAPTURE;
    }

    @Override
    public CardHolds.CaptureResult capturePaymentIntent(PaymentAccount account, String paymentIntentId,
                                                        String idempotencyKey) {
        if (down) {
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "The fake Stripe is down", null);
        }
        captureKeys.add(idempotencyKey);
        if (failNextCapture) {
            failNextCapture = false;
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "simulated: a 500 of Stripe", null);
        }
        if (refuseCaptures) {
            return new CardHolds.CaptureResult.Refused("payment_intent_unexpected_state");
        }
        captures.add(new Capture(account.name(), paymentIntentId, idempotencyKey));
        captured.add(paymentIntentId);
        if (loseNextCaptureResponse) {
            loseNextCaptureResponse = false;
            throw new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE, "simulated: response lost", null);
        }
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
        // as Stripe: a captured PaymentIntent can no longer be canceled
        if (captured.contains(paymentIntentId)) {
            return false;
        }
        cancels.add(new Cancel(account.name(), paymentIntentId, idempotencyKey));
        canceled.add(paymentIntentId);
        return true;
    }

    // everything forgotten, Stripe's state included: a test starts on a clean Stripe
    public void reset() {
        forgetCalls();
        captured.clear();
        canceled.clear();
        paidSessions.clear();
        expiredSessions.clear();
    }

    // only the calls and the switches forgotten; what happened on Stripe (captures, paid and expired sessions)
    // stays, as it would on the real one (the reconciliation, guide 33, part 6)
    public void forgetCalls() {
        calls.clear();
        cancels.clear();
        captures.clear();
        down = false;
        refuseCaptures = false;
        failAfterCapture = false;
        failNextCapture = false;
        loseNextCaptureResponse = false;
        captureKeys.clear();
    }

    public void failNextCapture() {
        failNextCapture = true;
    }

    public void loseNextCaptureResponse() {
        loseNextCaptureResponse = true;
    }

    public List<String> captureKeys() {
        return List.copyOf(captureKeys);
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
