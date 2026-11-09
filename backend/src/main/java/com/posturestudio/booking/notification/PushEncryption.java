package com.posturestudio.booking.notification;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encryption of a push message for one browser (RFC 8291, with the aes128gcm coding of RFC 8188;
 * guide 28, part 3): only that browser can read it, the push service carries it blind.
 *
 * JDK primitives composed in the order of the RFC, nothing invented: ECDH, HKDF (two HMAC-SHA-256),
 * AES-128-GCM. Checked byte for byte against the example of RFC 8291, Appendix A (PushEncryptionTest).
 * A pure function apart from the randomness, which the package-private overload takes from outside.
 */
final class PushEncryption {

    // the record size of the header; one record is enough for the few hundred bytes of our messages
    private static final int RECORD_SIZE = 4096;
    private static final int SALT_BYTES = 16;
    private static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = SALT_BYTES + 4 + 1 + P256.PUBLIC_KEY_BYTES;
    // what fits in one record: the plaintext, the delimiter and the tag
    static final int MAX_PLAINTEXT_BYTES = RECORD_SIZE - TAG_BYTES - 1 - HEADER_BYTES;

    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(US_ASCII);
    // after the plaintext of the last (here: only) record (RFC 8188, 2)
    private static final byte LAST_RECORD = 0x02;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PushEncryption() {
    }

    /**
     * @param browserPublicKey p256dh of the subscription, 65 bytes
     * @param authSecret       auth of the subscription, 16 bytes
     * @return the body of the request to the push service: header and ciphertext
     */
    static byte[] encrypt(byte[] browserPublicKey, byte[] authSecret, byte[] plaintext) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        // a new key pair for every message: no two messages share an AES key (guide 28, 3.1)
        return encrypt(browserPublicKey, authSecret, plaintext, P256.newKeyPair(), salt);
    }

    // with the key pair and the salt chosen by the caller: the test of the RFC example
    static byte[] encrypt(byte[] browserPublicKey, byte[] authSecret, byte[] plaintext, KeyPair serverKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("A push message holds at most " + MAX_PLAINTEXT_BYTES + " bytes");
        }
        if (authSecret.length != 16 || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("auth and salt are 16 bytes");
        }
        ECPublicKey browserKey = P256.publicKey(browserPublicKey);
        byte[] serverPublicKey = P256.encode((ECPublicKey) serverKeys.getPublic());
        try {
            // 1. the same secret on both sides, never transmitted
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(serverKeys.getPrivate());
            agreement.doPhase(browserKey, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // 2. combined with auth (HKDF extract, then expand): without auth no valid message can be made
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hmac(prkKey, concat(KEY_INFO, browserPublicKey, serverPublicKey, new byte[] {1}));

            // 3. key and nonce of AES-GCM, from the salt (HKDF again, with two different infos)
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[] {1})), 12);

            // 4. encrypted and authenticated: the 16-byte tag lets the browser refuse a modified message
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {LAST_RECORD}));

            // 5. the header tells the browser everything it needs to redo 1-3 with its own private key
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) P256.PUBLIC_KEY_BYTES)
                    .put(serverPublicKey);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
