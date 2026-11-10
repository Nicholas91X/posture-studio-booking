package com.posturestudio.booking.notification;

import static com.posturestudio.booking.notification.PushEncryptionTest.BROWSER_PUBLIC;
import static com.posturestudio.booking.notification.PushEncryptionTest.SERVER_PRIVATE;
import static com.posturestudio.booking.notification.PushEncryptionTest.SERVER_PUBLIC;
import static com.posturestudio.booking.notification.PushEncryptionTest.bytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The signature of the server (VAPID, RFC 8292; guide 28, part 4), without Spring. The header is read back
// as the push service reads it: the token split in three, the claims decoded, the signature verified with
// the public key in the format of JWT (64 bytes, r and s side by side)
class VapidTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final String SUBJECT = "mailto:studio@example.com";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Vapid vapid = new Vapid(SERVER_PUBLIC, SERVER_PRIVATE, SUBJECT, JSON, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theHeaderCarriesASignedTokenAndThePublicKey() throws Exception {
        String header = vapid.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc123"));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + SERVER_PUBLIC);
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode head = JSON.readTree(bytes(parts[0]));
        assertThat(head.get("alg").asString()).isEqualTo("ES256");
        JsonNode claims = JSON.readTree(bytes(parts[1]));
        // the origin of the endpoint, not the endpoint: no path
        assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.plus(Vapid.TOKEN_VALIDITY).getEpochSecond());
        assertThat(claims.get("sub").asString()).isEqualTo(SUBJECT);

        byte[] signature = bytes(parts[2]);
        assertThat(signature).hasSize(64);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(P256.publicKey(bytes(SERVER_PUBLIC)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(UTF_8));
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void theAudienceKeepsAPortThatIsNotTheDefault() {
        assertThat(Vapid.originOf(URI.create("http://localhost:8123/push/1"))).isEqualTo("http://localhost:8123");
        assertThat(Vapid.originOf(URI.create("https://web.push.apple.com/QGuQ"))).isEqualTo("https://web.push.apple.com");
    }

    // a copy error in .env stops the application at startup (guide 28, 5.3)
    @Test
    void twoKeysThatAreNotAPairStopTheStartup() {
        assertThatThrownBy(() -> new Vapid(BROWSER_PUBLIC, SERVER_PRIVATE, SUBJECT, JSON, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not a pair");
    }

    @Test
    void aMissingKeyStopsTheStartupWithoutShowingIt() {
        assertThatThrownBy(() -> new Vapid(SERVER_PUBLIC, "", SUBJECT, JSON, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID_PRIVATE_KEY");
        assertThatThrownBy(() -> new Vapid(SERVER_PUBLIC, "not-a-key", SUBJECT, JSON, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-a-key");
    }

    @Test
    void theSubjectMustBeAContact() {
        assertThatThrownBy(() -> new Vapid(SERVER_PUBLIC, SERVER_PRIVATE, "studio@example.com", JSON, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID_SUBJECT");
    }
}
