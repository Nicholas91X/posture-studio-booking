package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.CodeCheck.AccountDeactivated;
import com.posturestudio.booking.auth.CodeCheck.Expired;
import com.posturestudio.booking.auth.CodeCheck.Valid;
import com.posturestudio.booking.auth.CodeCheck.Wrong;
import com.posturestudio.booking.shared.DeliveryFailedException;
import com.posturestudio.booking.shared.TooManyRequestsException;

// Request and verification of access codes (guide 24, part 9). The service handles its own transactions
// (commit, then the email: guide 24, 7.5), so the test runs without one (NOT_SUPPORTED): every call really
// commits, as in production, and @AfterEach deletes what was written. Emails go to RecordingLoginCodeSender.
// The clock starts on Friday 6 November 2026, 10:00 in Rome, and moves on command
@DataJpaTest
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, LoginCodeService.class,
        LoginCodeHasher.class, LoginCodeConfiguration.class, RecordingLoginCodeSender.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginCodeServiceTest {

    private static final String MARIO = "mario@login.example";

    @Autowired
    LoginCodeService service;

    @Autowired
    RecordingLoginCodeSender sender;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private InetAddress home;

    @BeforeEach
    void startAgain() throws Exception {
        clock.reset();
        sender.clear();
        home = InetAddress.getByName("203.0.113.7");
    }

    // the container is shared with other test classes: delete what really got committed here
    @AfterEach
    void deleteWhatWasWritten() {
        jdbc.update("delete from login_code");
        jdbc.update("delete from app_user where email like '%@login.example'");
    }

    private String requestCode(String email) {
        service.request(email, home);
        return sender.lastCodeFor(email);
    }

    private static String wrong(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }

    private long accountOf(String email, String role, boolean active) {
        return jdbc.queryForObject("insert into app_user (role, email, active) values (?, ?, ?) returning id",
                Long.class, role, email, active);
    }

    // ***** request *****

    @Test
    void aRequestStoresTheFingerprintAndSendsTheCode() {
        service.request("  Mario@Login.Example ", home);

        String code = sender.lastCodeFor(MARIO);
        assertThat(code).matches("\\d{6}");
        Map<String, Object> row = jdbc.queryForMap("""
                select channel, destination, code_hash, host(requested_from) as requested_from, attempts, used_at,
                       expires_at - created_at = interval '10 minutes' as ten_minutes
                from login_code""");
        assertThat(row).containsEntry("channel", "EMAIL")
                .containsEntry("destination", MARIO)
                .containsEntry("requested_from", "203.0.113.7")
                .containsEntry("attempts", 0)
                .containsEntry("ten_minutes", true);
        assertThat(row.get("used_at")).isNull();
        // never the code in clear (D-014)
        assertThat((String) row.get("code_hash")).doesNotContain(code);
    }

    @Test
    void anIpv6RequestStoresOnlyItsNetwork() throws Exception {
        service.request(MARIO, InetAddress.getByName("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));

        assertThat(jdbc.queryForObject("select host(requested_from) from login_code", String.class))
                .isEqualTo("2001:db8:1:2::");
    }

    @Test
    void aMailServerThatDoesNotAnswerIsA503AndTheRowStays() {
        sender.failNext();

        assertThatThrownBy(() -> service.request(MARIO, home)).isInstanceOf(DeliveryFailedException.class);
        // committed before sending: it counts in the limits
        assertThat(jdbc.queryForObject("select count(*) from login_code", Long.class)).isEqualTo(1);
    }

    // ***** verification *****

    @Test
    void theRightCodeWorksOnce() {
        String code = requestCode(MARIO);

        assertThat(service.verify(MARIO, code)).isInstanceOf(Valid.class);
        assertThat(service.verify(MARIO, code)).isInstanceOf(Expired.class);
        assertThat(jdbc.queryForObject("select used_at is not null from login_code", Boolean.class)).isTrue();
    }

    @Test
    void theEmailIsNormalizedAlsoWhenVerifying() {
        String code = requestCode(MARIO);

        assertThat(service.verify(" MARIO@login.example", code)).isInstanceOf(Valid.class);
    }

    @Test
    void afterTenMinutesTheCodeHasExpired() {
        String code = requestCode(MARIO);
        clock.advance(Duration.ofMinutes(10));

        assertThat(service.verify(MARIO, code)).isInstanceOf(Expired.class);
    }

    @Test
    void withoutARequestThereIsNothingToCheck() {
        assertThat(service.verify(MARIO, "123456")).isInstanceOf(Expired.class);
    }

    // The trap of guide 24, 4.1: if the attempt were counted and then an exception thrown, the rollback
    // would cancel the count, and the right code would still pass after any number of wrong ones
    @Test
    void wrongAttemptsStayWrittenAndAfterFiveEvenTheRightCodeIsRefused() {
        String code = requestCode(MARIO);

        for (int i = 0; i < 4; i++) {
            assertThat(service.verify(MARIO, wrong(code))).isInstanceOf(Wrong.class);
        }
        // the fifth: a new code is needed at once
        assertThat(service.verify(MARIO, wrong(code))).isInstanceOf(Expired.class);
        assertThat(service.verify(MARIO, code)).isInstanceOf(Expired.class);
        assertThat(jdbc.queryForObject("select attempts from login_code", Integer.class)).isEqualTo(5);
    }

    @Test
    void aNewCodeMakesThePreviousOneUseless() {
        String first = requestCode(MARIO);
        String second = requestCode(MARIO);

        // one chance in a million that the two are equal: then the first is the second, and is right
        if (!first.equals(second)) {
            assertThat(service.verify(MARIO, first)).isInstanceOf(Wrong.class);
        }
        assertThat(service.verify(MARIO, second)).isInstanceOf(Valid.class);
    }

    @Test
    void anEmailNeverSeenBecomesACustomerAccountAtTheRightCode() {
        String code = requestCode(MARIO);
        // asking for a code creates nothing (guide 24, 5.2)
        assertThat(jdbc.queryForObject("select count(*) from app_user where email = ?", Long.class, MARIO)).isZero();

        CodeCheck first = service.verify(MARIO, code);

        assertThat(first).isInstanceOfSatisfying(Valid.class, valid -> {
            assertThat(valid.newAccount()).isTrue();
            Map<String, Object> user = jdbc.queryForMap("select email, role, active from app_user where id = ?",
                    valid.userId());
            assertThat(user).containsEntry("email", MARIO).containsEntry("role", "CUSTOMER")
                    .containsEntry("active", true);
        });
        // the second time, the same account and not a new one
        CodeCheck second = service.verify(MARIO, requestCode(MARIO));
        assertThat(second).isEqualTo(new Valid(((Valid) first).userId(), false));
    }

    @Test
    void aPractitionerSignsInWithTheirOwnAccount() {
        long anna = accountOf("anna@login.example", "PRACTITIONER", true);

        assertThat(service.verify("anna@login.example", requestCode("anna@login.example")))
                .isEqualTo(new Valid(anna, false));
    }

    @Test
    void aDeactivatedAccountReceivesNoCode() {
        accountOf(MARIO, "CUSTOMER", false);

        service.request(MARIO, home);

        // the email says "deactivated" instead of carrying a code; the row exists anyway (same work)
        assertThat(sender.sent()).containsExactly(new RecordingLoginCodeSender.Sent(MARIO, null));
        assertThat(jdbc.queryForObject("select count(*) from login_code", Long.class)).isEqualTo(1);
    }

    @Test
    void anAccountDeactivatedAfterTheRequestIsRefusedAndStaysDeactivated() {
        long mario = accountOf(MARIO, "CUSTOMER", true);
        String code = requestCode(MARIO);
        jdbc.update("update app_user set active = false where id = ?", mario);

        assertThat(service.verify(MARIO, code)).isInstanceOf(AccountDeactivated.class);
        // no automatic reactivation (guide 24, 5.4)
        assertThat(jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, mario)).isFalse();
    }

    // ***** limits (guide 24, part 6) *****

    @Test
    void theFourthCodeInFifteenMinutesIsRefusedUntilTheFirstLeavesTheWindow() {
        for (int i = 0; i < 3; i++) {
            service.request(MARIO, home);
        }

        assertThatThrownBy(() -> service.request(MARIO, home))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
        // nothing written, nothing sent
        assertThat(jdbc.queryForObject("select count(*) from login_code", Long.class)).isEqualTo(3);
        assertThat(sender.sent()).hasSize(3);

        clock.advance(Duration.ofMinutes(15));
        service.request(MARIO, home);
    }

    @Test
    void theLimitCountsUnknownEmailsToo() {
        // no account for MARIO: the same limit as a customer, so a 429 tells nothing
        for (int i = 0; i < 3; i++) {
            service.request(MARIO, home);
        }
        assertThatThrownBy(() -> service.request(MARIO, home)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void theEleventhCodeInADayIsRefused() {
        // three every quarter of an hour: never the short limit, ten in 45 minutes
        for (int i = 0; i < 10; i++) {
            service.request(MARIO, home);
            if (i % 3 == 2) {
                clock.advance(Duration.ofMinutes(15));
            }
        }
        clock.advance(Duration.ofMinutes(15));

        // an hour after the first code: room again 23 hours from now
        assertThatThrownBy(() -> service.request(MARIO, home))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(Duration.ofHours(23)));
    }

    @Test
    void thirtyRequestsAnHourFromTheSameIpv6NetworkThenNoMore() throws Exception {
        for (int i = 1; i <= 30; i++) {
            // a different address and a different email every time, the same /64
            service.request("person" + i + "@login.example", InetAddress.getByName("2001:db8:1:2::" + Integer.toHexString(i)));
        }

        assertThatThrownBy(() -> service.request("other@login.example", InetAddress.getByName("2001:db8:1:2::ffff")))
                .isInstanceOf(TooManyRequestsException.class);
        // another network is someone else
        service.request("other@login.example", InetAddress.getByName("2001:db8:1:3::1"));
    }

    // ***** cleanup (guide 24, 6.5) *****

    @Test
    void codesOlderThanADayAreDeleted() {
        service.request(MARIO, home);
        clock.advance(Duration.ofHours(23));
        service.request("anna@login.example", home);
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThat(service.purgeOld()).isEqualTo(1);
        assertThat(jdbc.queryForList("select destination from login_code", String.class))
                .containsExactly("anna@login.example");
    }

    // ***** parallel guesses (guide 24, 4.5) *****

    // Ten wrong codes at the same time. With the lock on the row the verifications go one at a time, and
    // each sees the attempts of the others: exactly four Wrong, then the fifth exhausts the code, then
    // Expired. Without it, parallel updates of the counter could be lost and allow more than five guesses
    @Test
    void tenParallelGuessesCountAsTenAndNeverGoBeyondFive() throws Exception {
        String code = requestCode(MARIO);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(10);
        try {
            List<Future<CodeCheck>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Callable<CodeCheck> guess = () -> {
                    start.await();
                    return service.verify(MARIO, wrong(code));
                };
                results.add(threads.submit(guess));
            }
            start.countDown();

            List<CodeCheck> checks = new ArrayList<>();
            for (Future<CodeCheck> result : results) {
                checks.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(checks).filteredOn(Wrong.class::isInstance).hasSize(4);
            assertThat(checks).filteredOn(Expired.class::isInstance).hasSize(6);
            assertThat(jdbc.queryForObject("select attempts from login_code", Integer.class)).isEqualTo(5);
        } finally {
            threads.shutdownNow();
        }
    }
}
