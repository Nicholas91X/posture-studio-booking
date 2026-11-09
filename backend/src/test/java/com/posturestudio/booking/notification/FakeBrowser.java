package com.posturestudio.booking.notification;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// The receiving side of RFC 8291, as a browser does it (guide 28, part 11): written here, in the tests, apart
// from PushEncryption, so that a round trip proves the message is readable, not that the production code
// agrees with itself. The browser has its private key, finds salt and server key in the header, derives the
// same key and nonce, and decrypts; AES-GCM refuses a body that was modified
final class FakeBrowser {

    private FakeBrowser() {
    }

    static byte[] decrypt(byte[] body, byte[] browserPrivate, byte[] browserPublic, byte[] auth) {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        int recordSize = in.getInt();
        int keyLength = in.get();
        byte[] serverPublic = new byte[keyLength];
        in.get(serverPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        if (recordSize != 4096 || keyLength != 65) {
            throw new AssertionError("unexpected header: rs=" + recordSize + ", idlen=" + keyLength);
        }
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(P256.privateKey(browserPrivate));
            agreement.doPhase(P256.publicKey(serverPublic), true);
            byte[] ecdh = agreement.generateSecret();

            byte[] ikm = hmac(hmac(auth, ecdh), join("WebPush: info\0".getBytes(US_ASCII), browserPublic, serverPublic, new byte[] {1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, join("Content-Encoding: aes128gcm\0".getBytes(US_ASCII), new byte[] {1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, join("Content-Encoding: nonce\0".getBytes(US_ASCII), new byte[] {1})), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(ciphertext);
            // the last record ends with 02, maybe followed by zeros of padding
            int end = padded.length - 1;
            while (padded[end] == 0) {
                end--;
            }
            if (padded[end] != 2) {
                throw new AssertionError("no last-record delimiter");
            }
            return Arrays.copyOf(padded, end);
        } catch (GeneralSecurityException e) {
            throw new AssertionError("the browser cannot decrypt this message", e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
