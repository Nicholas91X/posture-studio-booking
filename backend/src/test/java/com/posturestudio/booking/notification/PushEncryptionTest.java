package com.posturestudio.booking.notification;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.security.KeyPair;

import org.junit.jupiter.api.Test;

// The encryption of Web Push against the example of RFC 8291, Appendix A (guide 28, 2.3): with the keys and
// the salt of the RFC, our bytes must be the RFC's, all of them. If they are, the order of the steps, the
// info strings, the lengths and the header are right; a single wrong byte anywhere changes the whole result
class PushEncryptionTest {

    // Appendix A of RFC 8291, whitespace removed. Public on purpose: they protect nothing
    static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    static final String SERVER_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String SERVER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String BROWSER_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String BROWSER_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String HEADER = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    @Test
    void theExampleOfTheRfcGivesTheBytesOfTheRfc() {
        KeyPair server = new KeyPair(P256.publicKey(bytes(SERVER_PUBLIC)), P256.privateKey(bytes(SERVER_PRIVATE)));

        byte[] body = PushEncryption.encrypt(bytes(BROWSER_PUBLIC), bytes(AUTH), bytes(PLAINTEXT), server, bytes(SALT));

        assertThat(new String(bytes(PLAINTEXT), UTF_8)).isEqualTo("When I grow up, I want to be a watermelon");
        assertThat(body).isEqualTo(concat(bytes(HEADER), bytes(CIPHERTEXT)));
    }

    // with a random key pair and salt, what a browser reads is what was written (decrypted by test code)
    @Test
    void aBrowserReadsWhatWasWritten() {
        byte[] message = "{\"title\":\"Nuova prenotazione da approvare\"}".getBytes(UTF_8);

        byte[] body = PushEncryption.encrypt(bytes(BROWSER_PUBLIC), bytes(AUTH), message);

        assertThat(FakeBrowser.decrypt(body, bytes(BROWSER_PRIVATE), bytes(BROWSER_PUBLIC), bytes(AUTH))).isEqualTo(message);
        // two messages never share a key: different bodies for the same text
        assertThat(PushEncryption.encrypt(bytes(BROWSER_PUBLIC), bytes(AUTH), message)).isNotEqualTo(body);
    }

    @Test
    void aKeyOffTheCurveIsRefused() {
        byte[] offTheCurve = bytes(BROWSER_PUBLIC);
        offTheCurve[64] ^= 1;

        assertThatThrownBy(() -> P256.publicKey(offTheCurve))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on the P-256 curve");
    }

    @Test
    void aKeyOfTheWrongShapeIsRefused() {
        byte[] compressed = new byte[33];
        compressed[0] = 0x02;

        assertThatThrownBy(() -> P256.publicKey(compressed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageTooLargeForOneRecordIsRefused() {
        byte[] tooLarge = new byte[PushEncryption.MAX_PLAINTEXT_BYTES + 1];

        assertThatThrownBy(() -> PushEncryption.encrypt(bytes(BROWSER_PUBLIC), bytes(AUTH), tooLarge))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // a key read and written again is the same key: coordinates always on 32 bytes
    @Test
    void keysGoBackToTheirBytes() {
        assertThat(P256.encode(P256.publicKey(bytes(SERVER_PUBLIC)))).isEqualTo(bytes(SERVER_PUBLIC));
        assertThat(P256.encode(P256.privateKey(bytes(SERVER_PRIVATE)))).isEqualTo(bytes(SERVER_PRIVATE));
    }

    static byte[] bytes(String base64url) {
        return P256.fromBase64url(base64url);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
