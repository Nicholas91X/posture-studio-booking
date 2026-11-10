package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The constraints of V5 with plain SQL: the database defends itself even from a bug in Java.
// That the context starts at all proves the inet <-> InetAddress mapping (ddl-auto=validate).
// One violation per test: after an error PostgreSQL refuses every statement of the same transaction
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class LoginCodeSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private void insert(String channel, int attempts, String validity) {
        jdbc.update("""
                insert into login_code (channel, destination, code_hash, requested_from, created_at, expires_at, attempts)
                values (?, 'mario@example.com', 'ab', '203.0.113.7', now(), now() + cast(? as interval), ?)""",
                channel, validity, attempts);
    }

    @Test
    void aValidRowIsAccepted() {
        insert("EMAIL", 5, "10 minutes");
    }

    // V8 added SMS (guide 29); anything else is still refused (D-042)
    @Test
    void theChannelsAreEmailAndSms() {
        insert("SMS", 0, "10 minutes");
        assertThatThrownBy(() -> insert("WHATSAPP", 0, "10 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ***** V8: purpose, requested_by, message_sent (guide 29, part 9) *****
    // One violation per test: after a refused statement PostgreSQL aborts the whole transaction of the test
    // (25P02), and a second insert would fail for that reason, not for its own

    @Test
    void aVerificationCodeHasTheAccountThatAsked() {
        insertWithPurpose("SMS", "VERIFY_PHONE", aUser(), true);
    }

    @Test
    void aVerificationCodeWithoutTheAccountIsRefused() {
        assertThatThrownBy(() -> insertWithPurpose("SMS", "VERIFY_PHONE", null, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSignInCodeWithAnAccountThatAskedIsRefused() {
        long user = aUser();
        assertThatThrownBy(() -> insertWithPurpose("SMS", "SIGN_IN", user, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNumberIsNotVerifiedByEmail() {
        long user = aUser();
        assertThatThrownBy(() -> insertWithPurpose("EMAIL", "VERIFY_PHONE", user, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUnknownPurposeIsRefused() {
        assertThatThrownBy(() -> insertWithPurpose("SMS", "RESET", null, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // an email always leaves (D-050); only an SMS row may say that no message left
    @Test
    void anSmsMayHaveNoMessage() {
        insertWithPurpose("SMS", "SIGN_IN", null, false);
    }

    @Test
    void anEmailWithoutAMessageIsRefused() {
        assertThatThrownBy(() -> insertWithPurpose("EMAIL", "SIGN_IN", null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ***** V8: the phone of app_user *****

    @Test
    void aVerifiedPhoneInE164IsAccepted() {
        insertUser("v8-a@example.com", "+393331234567", true);
    }

    @Test
    void aPhoneNotInE164IsRefused() {
        assertThatThrownBy(() -> insertUser("v8-b@example.com", "333 123 4567", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPhoneWithoutItsVerificationIsRefused() {
        assertThatThrownBy(() -> insertUser("v8-c@example.com", "+393337654321", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aVerificationWithoutAPhoneIsRefused() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into app_user (role, email, phone_verified_at) values ('CUSTOMER', 'v8-d@example.com', now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // nobody takes the number of another without its code (guide 29, 2.4); many accounts without one
    @Test
    void aNumberBelongsToOneAccount() {
        insertUser("v8-e@example.com", "+393331234567", true);
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'v8-f@example.com')");
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'v8-g@example.com')");

        assertThatThrownBy(() -> insertUser("v8-h@example.com", "+393331234567", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ***** V9: the admin signs in by email only (D-056) *****

    @Test
    void anAdminWithAPhoneIsRefused() {
        assertThatThrownBy(() -> jdbc.update("insert into app_user (role, email, phone, phone_verified_at) "
                + "values ('ADMIN', 'v9-a@example.com', '+393331234567', now())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("app_user_admin_no_phone_ck");
    }

    @Test
    void anAdminWithoutAPhoneIsAccepted() {
        jdbc.update("insert into app_user (role, email) values ('ADMIN', 'v9-b@example.com')");
    }

    private long aUser() {
        return jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'schema-v8@example.com') returning id", Long.class);
    }

    private void insertWithPurpose(String channel, String purpose, Long requestedBy, boolean sent) {
        jdbc.update("""
                insert into login_code (channel, destination, code_hash, requested_from, created_at, expires_at,
                                        purpose, requested_by, message_sent)
                values (?, '+393331234567', 'ab', '203.0.113.7', now(), now() + interval '10 minutes', ?, ?, ?)""",
                channel, purpose, requestedBy, sent);
    }

    private void insertUser(String email, String phone, boolean verified) {
        jdbc.update("insert into app_user (role, email, phone, phone_verified_at) values ('CUSTOMER', ?, ?, "
                + (verified ? "now()" : "null") + ")", email, phone);
    }

    @Test
    void attemptsNeverGoBeyondFive() {
        assertThatThrownBy(() -> insert("EMAIL", 6, "10 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCodeExpiresAfterItWasCreated() {
        assertThatThrownBy(() -> insert("EMAIL", 0, "0 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
