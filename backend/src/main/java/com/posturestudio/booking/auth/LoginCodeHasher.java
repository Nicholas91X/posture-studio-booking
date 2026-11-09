package com.posturestudio.booking.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The fingerprint of an access code (guide 24, part 3): HMAC-SHA256 with a secret key kept outside the
 * database. A plain SHA-256 would protect nothing: there are only a million codes, and whoever reads the
 * table tries them all in less than a second. Without the key, the fingerprints are useless.
 *
 * The destination is part of the fingerprint: two people with the same code have different fingerprints,
 * and a fingerprint copied to another row is not valid there.
 *
 * The key is read with @Value and kept here, never in a record: the toString of a record prints all its
 * components, and a secret would end up in a log.
 */
@Component
class LoginCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";
    // as long as the output of SHA-256: a shorter key would be the weak part
    private static final int MIN_KEY_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    LoginCodeHasher(@Value("${auth.login-code.hmac-key}") String base64Key) {
        this.key = new SecretKeySpec(decode(base64Key), ALGORITHM);
    }

    // fail fast: an empty or short key stops the application at startup, not at the first login
    private static byte[] decode(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("auth.login-code.hmac-key (AUTH_CODE_HMAC_KEY) must be the Base64 of at least "
                    + MIN_KEY_BYTES + " random bytes, e.g. from: openssl rand -base64 32");
        }
        return bytes;
    }

    String hash(String destination, String code) {
        return HEX.formatHex(mac(destination, code));
    }

    // Constant time (guide 24, 3.4): MessageDigest.isEqual compares every byte, while equals stops at the
    // first difference and so its duration tells how much of the start was right
    boolean matches(String storedHash, String destination, String code) {
        return MessageDigest.isEqual(HEX.parseHex(storedHash), mac(destination, code));
    }

    // A Mac is not thread-safe: a new one for every call, which costs microseconds
    private byte[] mac(String destination, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal((destination + ":" + code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required in every JDK: this means a broken installation
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
