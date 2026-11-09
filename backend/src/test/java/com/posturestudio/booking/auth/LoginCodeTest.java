package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

// The rules of one code (D-014), without Spring and without a database
class LoginCodeTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static LoginCode newCode() throws Exception {
        return new LoginCode(LoginChannel.EMAIL, "mario@example.com", "00", InetAddress.getByName("203.0.113.7"), NOW);
    }

    @Test
    void aCodeStartingWithZerosKeepsItsSixDigits() {
        // a generator that always gives the smallest number: the code is 000000, not 0
        RandomGenerator alwaysZero = () -> 0L;

        assertThat(LoginCode.randomCode(alwaysZero)).isEqualTo("000000");
    }

    @Test
    void codesAreAlwaysSixDigits() {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 1_000; i++) {
            assertThat(LoginCode.randomCode(random)).matches("\\d{6}");
        }
    }

    @Test
    void aCodeIsValidForTenMinutes() throws Exception {
        LoginCode code = newCode();

        assertThat(code.usableAt(NOW.plus(Duration.ofMinutes(10)).minusNanos(1))).isTrue();
        assertThat(code.usableAt(NOW.plus(Duration.ofMinutes(10)))).isFalse();
    }

    @Test
    void afterFiveWrongAttemptsTheCodeIsExhausted() throws Exception {
        LoginCode code = newCode();
        for (int i = 0; i < 4; i++) {
            code.failedAttempt();
        }
        assertThat(code.exhausted()).isFalse();
        assertThat(code.usableAt(NOW)).isTrue();

        code.failedAttempt();

        assertThat(code.exhausted()).isTrue();
        assertThat(code.usableAt(NOW)).isFalse();
        // the database check stops at 5: the entity does not go beyond either
        assertThatThrownBy(code::failedAttempt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aUsedCodeCannotBeUsedAgain() throws Exception {
        LoginCode code = newCode();
        code.use(NOW);

        assertThat(code.usableAt(NOW)).isFalse();
        assertThatThrownBy(() -> code.use(NOW)).isInstanceOf(IllegalStateException.class);
    }

    // ***** what the per-IP limit counts (guide 24, 6.4) *****

    @Test
    void anIpv4AddressIsCountedAsItIs() throws Exception {
        InetAddress address = InetAddress.getByName("203.0.113.7");

        assertThat(LoginCodeService.limitKeyOf(address)).isEqualTo(address);
    }

    @Test
    void anIpv6AddressIsCountedByItsSlash64Network() throws Exception {
        InetAddress first = InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        InetAddress sameNetwork = InetAddress.getByName("2001:db8:1:2::1");
        InetAddress otherNetwork = InetAddress.getByName("2001:db8:1:3::1");

        assertThat(LoginCodeService.limitKeyOf(first)).isEqualTo(InetAddress.getByName("2001:db8:1:2::"));
        assertThat(LoginCodeService.limitKeyOf(sameNetwork)).isEqualTo(LoginCodeService.limitKeyOf(first));
        assertThat(LoginCodeService.limitKeyOf(otherNetwork)).isNotEqualTo(LoginCodeService.limitKeyOf(first));
    }
}
