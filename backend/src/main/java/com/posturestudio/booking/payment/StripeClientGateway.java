package com.posturestudio.booking.payment;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.booking.CardHolds;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.ServiceUnavailableException;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * The real StripeGateway, with stripe-java (guide 31). One StripeClient per account, built once at startup:
 * never the static Stripe.apiKey, which is one key for the whole application (guide 30, part 2).
 *
 * Timeouts much shorter than the library's (30 s to connect, 80 s to read, no retry): the call runs inside the
 * transaction of the request, holding the customer lock and the slot just inserted, so whoever asks for the
 * same slot waits for it (D-061, guide 31, 8.1). A deliberate choice, with a bounded worst case.
 *
 * Worst case: two attempts of at most 2 + 5 s, and the library's pause between them (0.5 s at the first
 * retry): about 15 s, then the customer gets a 503 and nothing is left. Checkout answers in well under a
 * second when Stripe is healthy. One retry, safe because every creation carries an idempotency key: a
 * timed-out POST that Stripe did process gets the same session back, not a second one.
 */
@Component
class StripeClientGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeClientGateway.class);

    static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    static final int READ_TIMEOUT_MILLIS = 5_000;
    static final int MAX_NETWORK_RETRIES = 1;

    // Stripe's code for "this PaymentIntent is not in a state that allows the request" (e.g. already canceled)
    static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private final Map<String, StripeClient> clients;

    // apiBase: Stripe's address; only the tests change it, to a fake HTTP server (guide 31, part 10)
    StripeClientGateway(PaymentAccounts accounts, @Value("${payment.stripe.api-base:https://api.stripe.com}") String apiBase) {
        this.clients = accounts.names().stream()
                .map(name -> accounts.byName(name).orElseThrow())
                .collect(Collectors.toUnmodifiableMap(PaymentAccount::name, account -> client(account, apiBase)));
    }

    private static StripeClient client(PaymentAccount account, String apiBase) {
        return StripeClient.builder()
                .setApiKey(account.secretKey())
                .setApiBase(apiBase)
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setReadTimeout(READ_TIMEOUT_MILLIS)
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .build();
    }

    @Override
    public CheckoutSession createCheckoutSession(PaymentAccount account, NewCheckoutSession session, String idempotencyKey) {
        String bookingId = Long.toString(session.bookingId());
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // the amount of the booking (D-011), not today's price; no product to create first on Stripe
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency("eur")
                                .setUnitAmount(session.amountCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(session.description())
                                        .build())
                                .build())
                        .build())
                // held, not captured (D-031); booking_id on the PaymentIntent too, for its events (piece 4)
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setCaptureMethod(SessionCreateParams.PaymentIntentData.CaptureMethod.MANUAL)
                        .putMetadata("booking_id", bookingId)
                        .build())
                // cards only, Apple Pay and Google Pay included (they are cards for Stripe)
                .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                .putMetadata("booking_id", bookingId)
                .setClientReferenceId(bookingId)
                // seconds, rounded down: the page never outlives the hold of the slot (guide 31, part 5)
                .setExpiresAt(session.expiresAt().getEpochSecond())
                .setCustomerEmail(session.customerEmail())
                .setLocale(SessionCreateParams.Locale.IT)
                .setSubmitType(SessionCreateParams.SubmitType.BOOK)
                .setSuccessUrl(session.successUrl().toString())
                .setCancelUrl(session.cancelUrl().toString())
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            Session created = clientOf(account).v1().checkout().sessions().create(params, options);
            return new CheckoutSession(created.getId(), URI.create(created.getUrl()));
        } catch (StripeException e) {
            throw unavailable("create a Checkout session for booking " + bookingId, account, e);
        }
    }

    @Override
    public CheckoutSessionStatus checkoutSessionStatus(PaymentAccount account, String checkoutSessionId) {
        try {
            return CheckoutSessionStatus.of(clientOf(account).v1().checkout().sessions().retrieve(checkoutSessionId).getStatus());
        } catch (StripeException e) {
            throw unavailable("read Checkout session " + checkoutSessionId, account, e);
        }
    }

    @Override
    public boolean expireCheckoutSession(PaymentAccount account, String checkoutSessionId, String idempotencyKey) {
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            clientOf(account).v1().checkout().sessions().expire(checkoutSessionId, options);
            return true;
        } catch (InvalidRequestException e) {
            // "already expired or isn't in an expireable state" (Stripe's documentation): not an error, the caller
            // reads the session again to know whether it was paid
            log.info("Checkout session {} on account {} not expired: no longer open (request {})", checkoutSessionId,
                    account.name(), e.getRequestId());
            return false;
        } catch (StripeException e) {
            throw unavailable("expire Checkout session " + checkoutSessionId, account, e);
        }
    }

    @Override
    public PaymentIntentStatus paymentIntentStatus(PaymentAccount account, String paymentIntentId) {
        try {
            // a GET: no idempotency key, reading is idempotent by nature
            return PaymentIntentStatus.of(clientOf(account).v1().paymentIntents().retrieve(paymentIntentId).getStatus());
        } catch (StripeException e) {
            throw unavailable("read PaymentIntent " + paymentIntentId, account, e);
        }
    }

    @Override
    public CardHolds.CaptureResult capturePaymentIntent(PaymentAccount account, String paymentIntentId,
                                                        String idempotencyKey) {
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            clientOf(account).v1().paymentIntents().capture(paymentIntentId, options);
            return new CardHolds.CaptureResult.Captured();
        } catch (CardException e) {
            log.warn("PaymentIntent {} on account {} not captured: card refused ({}, request {})", paymentIntentId,
                    account.name(), e.getDeclineCode(), e.getRequestId());
            return new CardHolds.CaptureResult.Refused("card_declined:" + e.getDeclineCode());
        } catch (InvalidRequestException e) {
            // no longer requires_capture: the hold expired after 7 days, or was released by hand (guide 33, part 2)
            if (UNEXPECTED_STATE.equals(e.getCode())) {
                log.warn("PaymentIntent {} on account {} not captured: no longer capturable (request {})",
                        paymentIntentId, account.name(), e.getRequestId());
                return new CardHolds.CaptureResult.Refused(e.getCode());
            }
            throw unavailable("capture PaymentIntent " + paymentIntentId, account, e);
        } catch (StripeException e) {
            throw unavailable("capture PaymentIntent " + paymentIntentId, account, e);
        }
    }

    @Override
    public boolean cancelPaymentIntent(PaymentAccount account, String paymentIntentId, String idempotencyKey) {
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        try {
            clientOf(account).v1().paymentIntents().cancel(paymentIntentId, options);
            return true;
        } catch (InvalidRequestException e) {
            // already canceled (from the dashboard, or the hold expired): the goal is reached, nothing to retry.
            // Asking again would get the same answer, and Stripe would send the event again for three days
            // Usually already canceled: fine. If it had been CAPTURED (guide 33, part 6, point 4), money was taken for
            // a booking that is not confirmed: an error, with what to do. No extra call to find out which
            if (UNEXPECTED_STATE.equals(e.getCode())) {
                log.error("PaymentIntent {} on account {} is no longer cancelable (request {}): fine if it was already"
                                + " canceled; if it was captured, refund it by hand from the Stripe dashboard (D-015)",
                        paymentIntentId, account.name(), e.getRequestId());
                return false;
            }
            throw unavailable("cancel PaymentIntent " + paymentIntentId, account, e);
        } catch (StripeException e) {
            throw unavailable("cancel PaymentIntent " + paymentIntentId, account, e);
        }
    }

    private StripeClient clientOf(PaymentAccount account) {
        StripeClient client = clients.get(account.name());
        if (client == null) {
            throw new IllegalStateException("No Stripe client for account " + account.name());
        }
        return client;
    }

    // Logged here, with what Stripe's support can find (its request id and code), never a key. Invalid
    // parameters are OUR mistake: an error, not a warning (guide 31, 8.2)
    private static ServiceUnavailableException unavailable(String what, PaymentAccount account, StripeException e) {
        String detail = "Stripe could not " + what + " on account " + account.name() + ": "
                + e.getClass().getSimpleName() + ", status " + e.getStatusCode() + ", code " + e.getCode()
                + ", request " + e.getRequestId();
        if (e instanceof InvalidRequestException) {
            log.error(detail, e);
        } else {
            log.warn(detail, e);
        }
        return new ServiceUnavailableException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
                "Payments are not available right now, try again in a moment", e);
    }
}
