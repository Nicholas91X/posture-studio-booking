package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// The SMS from the outside (guide 29, part 8), with real sessions: verify a number from the account, then
// sign in with it. @Transactional: MockMvc runs in the thread of the test, everything is rolled back.
// Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, RecordingLoginCodeSender.Configuration.class,
        RecordingSmsCodeSender.Configuration.class})
@Transactional
class SmsAccessApiTest {

    private static final String PHONE = "+393331234567";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    RecordingSmsCodeSender sms;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    private long mario;

    @BeforeEach
    void marioHasAnAccount() {
        sms.clear();
        mario = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'mario@sms-api.example') returning id", Long.class);
    }

    // the whole path: the number verified from the account, then a sign-in with it
    @Test
    void aVerifiedNumberSignsIn() {
        assertThat(post("/api/account/phone/code", "{\"phone\": \"333 123 4567\"}", true)).hasStatus(HttpStatus.ACCEPTED);
        String verification = sms.lastCodeFor(PHONE, CodePurpose.VERIFY_PHONE);

        assertThat(post("/api/account/phone", "{\"phone\": \"" + PHONE + "\", \"code\": \"" + verification + "\"}", true))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("{\"phone\": \"" + PHONE + "\", \"verifiedAt\": \"2026-11-06T09:00:00Z\"}");
        assertThat(mvc.get().uri("/api/account/phone").with(sessions.user(mario)))
                .bodyJson().extractingPath("$.phone").isEqualTo(PHONE);

        assertThat(post("/api/public/auth/login-code/sms", "{\"phone\": \"" + PHONE + "\"}", false))
                .hasStatus(HttpStatus.ACCEPTED);
        String signIn = sms.lastCodeFor(PHONE, CodePurpose.SIGN_IN);
        MvcTestResult session = post("/api/public/auth/session/sms",
                "{\"phone\": \"" + PHONE + "\", \"code\": \"" + signIn + "\"}", false);

        assertThat(session).hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("CUSTOMER");
        assertThat(session).cookies().containsCookie("SESSION");
    }

    // the same 202 for a number of nobody, and no SMS (guide 29, 4.1)
    @Test
    void aNumberOfNobodyGetsTheSameAnswer() {
        assertThat(post("/api/public/auth/login-code/sms", "{\"phone\": \"3339999999\"}", false))
                .hasStatus(HttpStatus.ACCEPTED);

        assertThat(sms.sent()).isEmpty();
    }

    @Test
    void aWrongSignInCodeIs401AsByEmail() {
        assertThat(post("/api/public/auth/session/sms", "{\"phone\": \"" + PHONE + "\", \"code\": \"123456\"}", false))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("CODE_EXPIRED");
    }

    // signed in: a 401 would make the frontend believe the session had ended (guide 29, 8.3)
    @Test
    void aWrongVerificationCodeIs409() {
        post("/api/account/phone/code", "{\"phone\": \"" + PHONE + "\"}", true);
        String wrong = sms.lastCodeFor(PHONE, CodePurpose.VERIFY_PHONE).equals("000000") ? "111111" : "000000";

        assertThat(post("/api/account/phone", "{\"phone\": \"" + PHONE + "\", \"code\": \"" + wrong + "\"}", true))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("CODE_WRONG");
    }

    // a format question, not a question about who has an account
    @Test
    void aNumberThatIsNotAnItalianMobileIs400OnItsField() {
        MvcTestResult result = post("/api/public/auth/login-code/sms", "{\"phone\": \"+44 7911 123456\"}", false);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("phone");
    }

    // the area of whoever is signed in, of any role (guide 29, 8.2)
    @Test
    void theAccountAreaIsForWhoeverIsSignedIn() {
        Long practitioner = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'anna@sms-api.example') returning id", Long.class);

        assertThat(mvc.get().uri("/api/account/phone")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/account/phone").with(sessions.user(practitioner)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("{\"phone\": null, \"verifiedAt\": null}");
    }

    @Test
    void theNumberIsRemoved() {
        jdbc.update("update app_user set phone = ?, phone_verified_at = now() where id = ?", PHONE, mario);

        assertThat(mvc.delete().uri("/api/account/phone").with(sessions.user(mario))).hasStatus(HttpStatus.NO_CONTENT);

        // the request joined the transaction of the test, which never commits: Hibernate writes on flush
        entityManager.flush();
        assertThat(jdbc.queryForObject("select phone from app_user where id = ?", String.class, mario)).isNull();
    }

    private MvcTestResult post(String uri, String body, boolean asMario) {
        return mvc.post().uri(uri).with(asMario ? sessions.user(mario) : csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
