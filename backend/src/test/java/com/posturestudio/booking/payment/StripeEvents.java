package com.posturestudio.booking.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe events as Stripe sends them, and their signature computed BY HAND (guide 32, part 11): if the
 * library both signed and verified, a mistake of ours in using it (the wrong secret, a changed body) could
 * pass unnoticed. The scheme of guide 32, part 3: HMAC-SHA256(secret, timestamp + "." + body), in hex.
 */
public final class StripeEvents {

    private StripeEvents() {
    }

    // the Stripe-Signature header for this body
    public static String signature(String body, String webhookSecret, Instant at) {
        long timestamp = at.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // with manual capture the session is complete but "unpaid": the money is held (guide 32, part 2)
    public static String sessionCompleted(String eventId, String sessionId, String paymentIntentId) {
        return event(eventId, "checkout.session.completed", """
                {"id": "%s", "object": "checkout.session", "status": "complete", "payment_status": "unpaid",
                 "payment_intent": "%s", "mode": "payment", "amount_total": 3000, "currency": "eur"}
                """.formatted(sessionId, paymentIntentId));
    }

    // a package, captured at once: complete AND paid (guide 37, part 7). created: when Stripe completed it
    public static String packageSessionCompleted(String eventId, String sessionId, String paymentIntentId,
                                                 String paymentStatus, Instant created) {
        return event(eventId, "checkout.session.completed", """
                {"id": "%s", "object": "checkout.session", "status": "complete", "payment_status": "%s",
                 "payment_intent": "%s", "mode": "payment", "amount_total": 12000, "currency": "eur"}
                """.formatted(sessionId, paymentStatus, paymentIntentId), created);
    }

    public static String sessionExpired(String eventId, String sessionId) {
        return event(eventId, "checkout.session.expired", """
                {"id": "%s", "object": "checkout.session", "status": "expired", "payment_status": "unpaid",
                 "payment_intent": null, "mode": "payment"}
                """.formatted(sessionId));
    }

    // created 1793955600: Friday 6 November 2026, 09:00 UTC, the fixed clock of the tests
    public static String event(String eventId, String type, String object) {
        return event(eventId, type, object, Instant.ofEpochSecond(1793955600L));
    }

    public static String event(String eventId, String type, String object, Instant created) {
        return """
                {"id": "%s", "object": "event", "api_version": "2026-10-28.endive", "created": %d,
                 "livemode": false, "pending_webhooks": 1, "type": "%s", "data": {"object": %s}}
                """.formatted(eventId, created.getEpochSecond(), type, object);
    }
}
