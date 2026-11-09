package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

// The fingerprint of guide 24, part 3, without Spring
class LoginCodeHasherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("first-test-key-of-at-least-32-bytes".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("other-test-key-of-at-least-32-bytes".getBytes());

    private final LoginCodeHasher hasher = new LoginCodeHasher(KEY);

    @Test
    void theSameCodeForTheSameDestinationHasTheSameFingerprint() {
        String hash = hasher.hash("mario@example.com", "482913");

        assertThat(hasher.hash("mario@example.com", "482913")).isEqualTo(hash);
        assertThat(hasher.matches(hash, "mario@example.com", "482913")).isTrue();
    }

    @Test
    void theFingerprintDoesNotContainTheCode() {
        // HMAC-SHA256 in hex: 64 characters, nothing to read in them
        assertThat(hasher.hash("mario@example.com", "482913")).hasSize(64).doesNotContain("482913");
    }

    @Test
    void aWrongCodeDoesNotMatch() {
        String hash = hasher.hash("mario@example.com", "482913");

        assertThat(hasher.matches(hash, "mario@example.com", "482914")).isFalse();
    }

    @Test
    void theSameCodeForAnotherDestinationHasAnotherFingerprint() {
        String hash = hasher.hash("mario@example.com", "482913");

        assertThat(hasher.hash("anna@example.com", "482913")).isNotEqualTo(hash);
        // a fingerprint copied to the row of someone else is not valid there
        assertThat(hasher.matches(hash, "anna@example.com", "482913")).isFalse();
    }

    @Test
    void withoutTheKeyTheFingerprintCannotBeReproduced() {
        LoginCodeHasher otherKey = new LoginCodeHasher(OTHER_KEY);

        assertThat(otherKey.hash("mario@example.com", "482913"))
                .isNotEqualTo(hasher.hash("mario@example.com", "482913"));
    }

    @Test
    void anEmptyOrShortOrMalformedKeyStopsTheApplication() {
        // as with AUTH_CODE_HMAC_KEY= left empty in .env
        assertThatThrownBy(() -> new LoginCodeHasher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LoginCodeHasher(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LoginCodeHasher("not base64 !")).isInstanceOf(IllegalStateException.class);
    }
}
