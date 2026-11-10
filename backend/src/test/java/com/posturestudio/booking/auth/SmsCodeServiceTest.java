package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

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
import com.posturestudio.booking.auth.CodeCheck.Expired;
import com.posturestudio.booking.auth.CodeCheck.Valid;
import com.posturestudio.booking.auth.CodeCheck.Wrong;
import com.posturestudio.booking.shared.TooManyRequestsException;

// The codes by SMS (guide 29): sign-in to a verified number, and the verification of a number. As
// LoginCodeServiceTest, without a test transaction: every call really commits, @AfterEach deletes.
// SMS go to RecordingSmsCodeSender, emails to RecordingLoginCodeSender
@DataJpaTest
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, LoginCodeService.class,
        LoginCodeHasher.class, LoginCodeConfiguration.class, RecordingLoginCodeSender.Configuration.class,
        RecordingSmsCodeSender.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SmsCodeServiceTest {

    private static final String ANNA_PHONE = "+393331234567";
    private static final String NOBODYS_PHONE = "+393339999999";

    @Autowired
    LoginCodeService service;

    @Autowired
    RecordingSmsCodeSender sms;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private InetAddress home;

    @BeforeEach
    void startAgain() throws Exception {
        clock.reset();
        sms.clear();
        home = InetAddress.getByName("203.0.113.7");
    }

    @AfterEach
    void deleteWhatWasWritten() {
        jdbc.update("delete from login_code");
        jdbc.update("delete from app_user where email like '%@sms.example'");
    }

    // ***** sign-in by SMS (guide 29, part 4) *****

    @Test
    void aVerifiedNumberGetsTheSmsAndItsCodeSignsIn() {
        long anna = account("anna@sms.example", ANNA_PHONE, true);

        // written as people write it: the same number
        service.requestSms("333 123 4567", home);

        String code = sms.lastCodeFor(ANNA_PHONE, CodePurpose.SIGN_IN);
        assertThat(service.verifySms(ANNA_PHONE, code)).isEqualTo(new Valid(anna, Role.CUSTOMER, false));
    }

    // no SMS, but a row: the limits must behave as for a known number (guide 29, 4.1). Its code, which
    // nobody received, opens nothing, and no account is born from a number
    @Test
    void aNumberOfNobodyGetsNoSmsAndNoAccount() {
        service.requestSms(NOBODYS_PHONE, home);

        assertThat(sms.sent()).isEmpty();
        assertThat(jdbc.queryForObject("select message_sent from login_code where destination = ?", Boolean.class,
                NOBODYS_PHONE)).isFalse();
        assertThat(service.verifySms(NOBODYS_PHONE, "123456")).isInstanceOf(Wrong.class);
        assertThat(jdbc.queryForObject("select count(*) from app_user where phone = ?", Long.class, NOBODYS_PHONE)).isZero();
    }

    // the same 429 at the same request, known or not: the limits tell nothing
    @Test
    void theLimitsAreTheSameForAKnownNumberAndForANumberOfNobody() {
        account("anna@sms.example", ANNA_PHONE, true);
        for (int request = 0; request < 3; request++) {
            service.requestSms(ANNA_PHONE, home);
            service.requestSms(NOBODYS_PHONE, home);
        }

        assertThatThrownBy(() -> service.requestSms(ANNA_PHONE, home)).isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> service.requestSms(NOBODYS_PHONE, home)).isInstanceOf(TooManyRequestsException.class);
    }

    // an SMS costs, and the message would serve nothing (guide 29, 4.4)
    @Test
    void aDeactivatedAccountGetsNoSms() {
        account("anna@sms.example", ANNA_PHONE, false);

        service.requestSms(ANNA_PHONE, home);

        assertThat(sms.sent()).isEmpty();
    }

    // the cap counts the SMS that left (guide 29, 4.3; 50 a day, D-056): 50 codes for numbers of nobody do
    // not exhaust it, 50 SMS that left do, for every number
    @Test
    void theDailyCapCountsOnlyTheSmsThatLeft() {
        account("anna@sms.example", ANNA_PHONE, true);
        insertSmsRows(50, false);
        service.requestSms(ANNA_PHONE, home);
        assertThat(sms.sent()).hasSize(1);

        // 49 left with this one: the fiftieth still leaves, the fifty-first does not
        insertSmsRows(48, true);
        service.requestSms(ANNA_PHONE, home);
        assertThat(sms.sent()).hasSize(2);

        assertThatThrownBy(() -> service.requestSms(ANNA_PHONE, home)).isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> service.requestSms(NOBODYS_PHONE, home)).isInstanceOf(TooManyRequestsException.class);
    }

    // ***** verification of a number (guide 29, part 3) *****

    @Test
    void theRightCodeMakesTheNumberTheAccounts() {
        long anna = account("anna@sms.example", null, true);

        service.requestPhoneVerification(anna, "333 123 4567", home);
        String code = sms.lastCodeFor(ANNA_PHONE, CodePurpose.VERIFY_PHONE);

        assertThat(service.confirmPhone(anna, ANNA_PHONE, code)).isInstanceOf(PhoneCheck.Verified.class);
        assertThat(service.phoneOf(anna).phone()).isEqualTo(ANNA_PHONE);
        assertThat(service.phoneOf(anna).verifiedAt()).isEqualTo(clock.instant());
    }

    // the attempt stays written: no exception, the transaction commits (guide 24, 4.1)
    @Test
    void aWrongCodeCountsTheAttempt() {
        long anna = account("anna@sms.example", null, true);
        service.requestPhoneVerification(anna, ANNA_PHONE, home);

        assertThat(service.confirmPhone(anna, ANNA_PHONE, "000000")).isInstanceOf(PhoneCheck.Wrong.class);
        assertThat(jdbc.queryForObject("select attempts from login_code", Integer.class)).isOne();
        assertThat(service.phoneOf(anna).phone()).isNull();
    }

    // the code confirms the number for the account that asked, no other (guide 29, 3.3)
    @Test
    void anotherAccountCannotUseTheCode() {
        long anna = account("anna@sms.example", null, true);
        long bruno = account("bruno@sms.example", null, true);
        service.requestPhoneVerification(anna, ANNA_PHONE, home);
        String annasCode = sms.lastCodeFor(ANNA_PHONE, CodePurpose.VERIFY_PHONE);

        assertThat(service.confirmPhone(bruno, ANNA_PHONE, annasCode)).isInstanceOf(PhoneCheck.Expired.class);
        assertThat(service.phoneOf(bruno).phone()).isNull();
    }

    // same table, never one for the other (guide 29, 3.2)
    @Test
    void aVerificationCodeDoesNotSignInAndASignInCodeDoesNotVerify() {
        long anna = account("anna@sms.example", ANNA_PHONE, true);
        service.requestPhoneVerification(anna, ANNA_PHONE, home);
        assertThat(service.verifySms(ANNA_PHONE, sms.lastCodeFor(ANNA_PHONE, CodePurpose.VERIFY_PHONE)))
                .isInstanceOf(Expired.class);

        service.requestSms(ANNA_PHONE, home);
        assertThat(service.confirmPhone(anna, ANNA_PHONE, sms.lastCodeFor(ANNA_PHONE, CodePurpose.SIGN_IN)))
                .isInstanceOf(PhoneCheck.Wrong.class);
    }

    // numbers are recycled: whoever has the phone now takes it (guide 29, 3.4)
    @Test
    void theNumberMovesToWhoVerifiesIt() {
        long previous = account("vecchio@sms.example", ANNA_PHONE, true);
        long carla = account("carla@sms.example", null, true);

        service.requestPhoneVerification(carla, ANNA_PHONE, home);
        service.confirmPhone(carla, ANNA_PHONE, sms.lastCodeFor(ANNA_PHONE, CodePurpose.VERIFY_PHONE));

        assertThat(service.phoneOf(carla).phone()).isEqualTo(ANNA_PHONE);
        assertThat(service.phoneOf(previous).phone()).isNull();
    }

    // the door SMS pumping knocks at (guide 29, 5.2): five numbers a day per account
    @Test
    void fiveVerificationsADayPerAccount() {
        long anna = account("anna@sms.example", null, true);
        for (int number = 0; number < 5; number++) {
            service.requestPhoneVerification(anna, "+39333100000" + number, home);
        }

        assertThatThrownBy(() -> service.requestPhoneVerification(anna, "+393331000009", home))
                .isInstanceOf(TooManyRequestsException.class);
    }

    // D-056: the guard of AppUser, under the rule of SecurityConfiguration that already stops the admin at
    // the door (SmsAccessApiTest) and above the check of V9 (LoginCodeSchemaTest). Here the service is
    // called directly, as a door forgotten open would
    @Test
    void theAdminNeverGetsANumber() {
        long admin = jdbc.queryForObject(
                "insert into app_user (role, email) values ('ADMIN', 'admin@sms.example') returning id", Long.class);
        service.requestPhoneVerification(admin, ANNA_PHONE, home);
        String code = sms.lastCodeFor(ANNA_PHONE, CodePurpose.VERIFY_PHONE);

        assertThatThrownBy(() -> service.confirmPhone(admin, ANNA_PHONE, code))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("D-056");
        assertThat(service.phoneOf(admin).phone()).isNull();
    }

    @Test
    void theNumberCanBeRemoved() {
        long anna = account("anna@sms.example", ANNA_PHONE, true);

        service.removePhone(anna);

        assertThat(service.phoneOf(anna)).isEqualTo(AccountPhoneView.NONE);
    }

    // ***** helpers *****

    private long account(String email, String phone, boolean active) {
        return jdbc.queryForObject("""
                insert into app_user (role, email, active, phone, phone_verified_at)
                values ('CUSTOMER', ?, ?, ?, case when cast(? as text) is null then null else now() end) returning id""",
                Long.class, email, active, phone, phone);
    }

    // SMS rows of the last hours for other numbers, as other requests would have left them
    private void insertSmsRows(int count, boolean sent) {
        for (int row = 0; row < count; row++) {
            jdbc.update("""
                    insert into login_code (channel, destination, code_hash, requested_from, created_at, expires_at,
                                            purpose, message_sent)
                    values ('SMS', ?, 'ab', '198.51.100.1', cast(? as timestamptz) - interval '1 hour',
                            cast(? as timestamptz) + interval '10 minutes', 'SIGN_IN', ?)""",
                    "+39334" + String.format("%07d", row) + (sent ? "1" : "0"),
                    java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()), sent);
        }
    }
}
