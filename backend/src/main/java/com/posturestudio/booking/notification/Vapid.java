package com.posturestudio.booking.notification;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * The signature of our server on every push request (VAPID, RFC 8292; guide 28, part 4): the push service
 * accepts on an endpoint only the requests signed with the key whose public half the browser got when it
 * subscribed.
 *
 * Fail fast (guide 28, 5.3): without the keys, or with two keys that are not a pair, the application does
 * not start. The private key stays in this object, never in a record or a log, as the HMAC key of the
 * access codes (LoginCodeHasher).
 */
@Component
class Vapid {

    // RFC 8292 allows at most 24 hours
    static final Duration TOKEN_VALIDITY = Duration.ofHours(12);

    // ES256 wants r and s side by side, 64 bytes (IEEE P1363), not the DER of "SHA256withECDSA" (guide 28, 4.3)
    private static final String SIGNATURE = "SHA256withECDSAinP1363Format";
    private static final String HEADER = P256.base64url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(UTF_8));

    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String publicKeyText;
    private final String subject;
    private final JsonMapper json;
    private final Clock clock;

    Vapid(@Value("${notification.push.vapid.public-key}") String publicKey,
          @Value("${notification.push.vapid.private-key}") String privateKey,
          @Value("${notification.push.vapid.subject}") String subject,
          JsonMapper json, Clock clock) {
        this.publicKey = readPublic(publicKey);
        this.privateKey = readPrivate(privateKey);
        this.publicKeyText = P256.base64url(P256.encode(this.publicKey));
        this.subject = requireSubject(subject);
        this.json = json;
        this.clock = clock;
        requirePair();
    }

    // what the frontend gives the browser as applicationServerKey (guide 28, 4.1)
    String publicKey() {
        return publicKeyText;
    }

    // The Authorization header for one endpoint: "vapid t=<JWT>, k=<public key>"
    String authorization(URI endpoint) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", originOf(endpoint));
        claims.put("exp", clock.instant().plus(TOKEN_VALIDITY).getEpochSecond());
        claims.put("sub", subject);
        String unsigned = HEADER + "." + P256.base64url(json.writeValueAsBytes(claims));
        return "vapid t=" + unsigned + "." + P256.base64url(sign(unsigned.getBytes(UTF_8))) + ", k=" + publicKeyText;
    }

    // the audience is the origin: scheme, host and, if not the default, port. A token for Google is no
    // good for Apple
    static String originOf(URI endpoint) {
        return endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
    }

    private byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance(SIGNATURE);
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID signature failed", e);
        }
    }

    // a copy error in .env is found here, at startup, not when no notification arrives
    private void requirePair() {
        byte[] probe = "vapid key pair check".getBytes(UTF_8);
        try {
            Signature verifier = Signature.getInstance(SIGNATURE);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            if (!verifier.verify(sign(probe))) {
                throw new IllegalStateException("VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are not a pair: "
                        + "generate them together with: java tools/GenerateVapidKeys.java");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID key check failed", e);
        }
    }

    private static ECPublicKey readPublic(String text) {
        try {
            return P256.publicKey(P256.fromBase64url(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("notification.push.vapid.public-key (VAPID_PUBLIC_KEY) must be the base64url "
                    + "of a P-256 public key: generate the pair with: java tools/GenerateVapidKeys.java");
        }
    }

    // no cause attached and nothing of the value in the message: it is a secret
    private static ECPrivateKey readPrivate(String text) {
        try {
            return P256.privateKey(P256.fromBase64url(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("notification.push.vapid.private-key (VAPID_PRIVATE_KEY) must be the base64url "
                    + "of a P-256 private key: generate the pair with: java tools/GenerateVapidKeys.java");
        }
    }

    // a contact for the push service: Apple refuses a token without a valid one
    private static String requireSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        if (!value.startsWith("mailto:") && !value.startsWith("https://")) {
            throw new IllegalStateException("notification.push.vapid.subject (VAPID_SUBJECT) must be a mailto: or https: URL");
        }
        return value;
    }
}
