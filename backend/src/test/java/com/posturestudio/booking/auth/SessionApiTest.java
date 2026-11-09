package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

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
import jakarta.servlet.http.Cookie;

// The session from the outside (guide 25, part 9): the whole sign-in with the code, the cookie, session
// fixation, sign-out, durations, and the profile after the first sign-in. Same configuration as
// LoginCodeApiTest: same Spring context. Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, RecordingLoginCodeSender.Configuration.class})
@Transactional
class SessionApiTest {

    private static final String SESSION = "/api/public/auth/session";
    private static final String MARIO = "mario@example.com";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    RecordingLoginCodeSender sender;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @BeforeEach
    void noEmailsYet() {
        sender.clear();
    }

    // ***** helpers: the real path, code by email and then sign-in *****

    private String requestCode(String email) {
        assertThat(mvc.post().uri("/api/public/auth/login-code").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + email + "\"}"))
                .hasStatus(HttpStatus.ACCEPTED);
        return sender.lastCodeFor(email);
    }

    private MvcTestResult signIn(String email, String code) {
        return mvc.post().uri(SESSION).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"code\": \"" + code + "\"}")
                .exchange();
    }

    // the Set-Cookie of the session, as Spring Session writes it
    private static String sessionSetCookie(MvcTestResult result) {
        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        return cookies.stream().filter(c -> c.startsWith("SESSION=")).findFirst()
                .orElseThrow(() -> new AssertionError("No session cookie in " + cookies));
    }

    private static Cookie sessionCookie(MvcTestResult result) {
        String header = sessionSetCookie(result);
        return new Cookie("SESSION", header.substring("SESSION=".length(), header.indexOf(';')));
    }

    private static String sessionId(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    // An update of an account behind Hibernate's back. The test is @Transactional, so all its MockMvc
    // requests share ONE transaction and ONE persistence context: an AppUser loaded by an earlier request
    // would be returned as it was, stale. In production every request has its own transaction, and this
    // cannot happen. So: pending changes written first, the cache emptied after
    private void updateAccount(String sql, long id) {
        entityManager.flush();
        jdbc.update(sql, id);
        entityManager.clear();
    }

    private long accountOf(String email, String role) {
        return jdbc.queryForObject("insert into app_user (role, email) values (?, ?) returning id", Long.class, role, email);
    }

    // ***** sign-in *****

    @Test
    void theCodeOpensASessionWithASafeCookie() {
        MvcTestResult result = signIn(MARIO, requestCode(MARIO));

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("CUSTOMER");
        // guide 25, 5.1 and 4.4: not readable by JavaScript, HTTPS only, not sent by other sites, 90 days
        assertThat(sessionSetCookie(result))
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=" + Duration.ofDays(90).toSeconds());

        // the next request is recognized by the cookie alone
        assertThat(mvc.get().uri(SESSION).cookie(sessionCookie(result)))
                .hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void aPractitionerSignsInWithTheirRole() {
        accountOf("anna@example.com", "PRACTITIONER");

        assertThat(signIn("anna@example.com", requestCode("anna@example.com")))
                .hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("PRACTITIONER");
    }

    // Session fixation (guide 25, 5.3): an id known before the sign-in is worth nothing after it
    @Test
    void theSessionIdChangesAtTheSignInAndTheOldOneIsDead() {
        long anna = accountOf("anna@example.com", "PRACTITIONER");
        Cookie before = sessions.cookie(anna, FixedClockConfiguration.NOW);

        MvcTestResult result = mvc.post().uri(SESSION).with(csrf()).cookie(before)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + MARIO + "\", \"code\": \"" + requestCode(MARIO) + "\"}")
                .exchange();

        assertThat(result).hasStatusOk();
        Cookie after = sessionCookie(result);
        assertThat(sessionId(after)).isNotEqualTo(sessionId(before));
        assertThat(mvc.get().uri(SESSION).cookie(before)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(SESSION).cookie(after))
                .hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void withoutASessionNobodyIsIn() {
        assertThat(mvc.get().uri(SESSION))
                .hasStatus(HttpStatus.UNAUTHORIZED).hasContentType("application/problem+json");
    }

    @Test
    void signingOutKillsTheSession() {
        Cookie cookie = sessionCookie(signIn(MARIO, requestCode(MARIO)));

        assertThat(mvc.delete().uri(SESSION).cookie(cookie).with(csrf())).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get().uri(SESSION).cookie(cookie)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ***** the outcomes of the code (CodeCheck) *****

    @Test
    void aWrongCodeIs401WithItsCode() {
        String code = requestCode(MARIO);
        String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);

        MvcTestResult result = signIn(MARIO, wrong);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("CODE_WRONG");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
    }

    @Test
    void withoutACodeToCheckTheAnswerIsExpired() {
        assertThat(signIn(MARIO, "123456"))
                .hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("CODE_EXPIRED");
    }

    @Test
    void aCodeThatIsNotSixDigitsIsA400AndUsesNoAttempt() {
        requestCode(MARIO);

        assertThat(signIn(MARIO, "12ab56"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("code");
        assertThat(jdbc.queryForObject("select attempts from login_code where destination = ?", Integer.class, MARIO))
                .isZero();
    }

    @Test
    void aDeactivatedAccountIs403EvenWithTheRightCode() {
        long mario = accountOf(MARIO, "CUSTOMER");
        String code = requestCode(MARIO);
        updateAccount("update app_user set active = false where id = ?", mario);

        assertThat(signIn(MARIO, code))
                .hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_DEACTIVATED");
    }

    @Test
    void theSignInNeedsTheCsrfTokenToo() {
        // against "login CSRF" (guide 25, 5.2)
        MvcTestResult result = mvc.post().uri(SESSION).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + MARIO + "\", \"code\": \"" + requestCode(MARIO) + "\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code").isEqualTo("CSRF_TOKEN_INVALID");
    }

    // ***** durations (guide 25, 4.4) *****

    @Test
    void aSessionEndsAfterThirtyDaysWithoutRequests() {
        Cookie cookie = sessionCookie(signIn(MARIO, requestCode(MARIO)));

        // the inactivity is counted by Spring Session with the real time: here its setting is what is proved
        Integer seconds = jdbc.queryForObject("select max_inactive_interval from spring_session where session_id = ?",
                Integer.class, sessionId(cookie));
        assertThat(Duration.ofSeconds(seconds)).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void aSessionEndsNinetyDaysAfterTheSignInHoweverActive() {
        long anna = accountOf("anna@example.com", "PRACTITIONER");

        assertThat(mvc.get().uri(SESSION).with(sessions.user(anna, FixedClockConfiguration.NOW.minus(Duration.ofDays(89)))))
                .hasStatusOk();
        assertThat(mvc.get().uri(SESSION).with(sessions.user(anna, FixedClockConfiguration.NOW.minus(Duration.ofDays(90)))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // A deactivated account is out at its next request, with a session already open (guide 25, 4.2)
    @Test
    void anAccountDeactivatedWithAnOpenSessionIsOutAtOnceAndForGood() {
        long anna = accountOf("anna@example.com", "PRACTITIONER");
        Cookie cookie = sessions.cookie(anna, FixedClockConfiguration.NOW);
        assertThat(mvc.get().uri(SESSION).cookie(cookie)).hasStatusOk();

        updateAccount("update app_user set active = false where id = ?", anna);
        assertThat(mvc.get().uri(SESSION).cookie(cookie)).hasStatus(HttpStatus.UNAUTHORIZED);

        // the session was destroyed, not only refused: reactivating the account does not bring it back
        updateAccount("update app_user set active = true where id = ?", anna);
        assertThat(mvc.get().uri(SESSION).cookie(cookie)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRoleIsTheOneOfNowNotTheOneOfTheSignIn() {
        long user = accountOf("anna@example.com", "CUSTOMER");
        Cookie cookie = sessions.cookie(user, FixedClockConfiguration.NOW);

        updateAccount("update app_user set role = 'ADMIN' where id = ?", user);

        assertThat(mvc.get().uri(SESSION).cookie(cookie))
                .hasStatusOk().bodyJson().extractingPath("$.role").isEqualTo("ADMIN");
    }

    // ***** the profile after the first sign-in (D-050, guide 25, 7.3) *****

    @Test
    void aNewCustomerGivesTheirNamesOnceAndThenBooks() {
        Cookie cookie = sessionCookie(signIn(MARIO, requestCode(MARIO)));

        assertThat(mvc.get().uri("/api/customer/profile").cookie(cookie))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("PROFILE_INCOMPLETE");
        assertThat(mvc.get().uri("/api/customer/bookings").cookie(cookie))
                .hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.code").isEqualTo("PROFILE_INCOMPLETE");

        assertThat(mvc.put().uri("/api/customer/profile").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": \"Mario\", \"lastName\": \"Rosi\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.email").isEqualTo(MARIO);
        // a typo, corrected by the customer: PUT again
        assertThat(mvc.put().uri("/api/customer/profile").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": \"Mario\", \"lastName\": \"Rossi\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.lastName").isEqualTo("Rossi");

        assertThat(mvc.get().uri("/api/customer/bookings").cookie(cookie)).hasStatusOk();
        assertThat(jdbc.queryForObject("select count(*) from customer c join app_user u on u.id = c.user_id where u.email = ?",
                Long.class, MARIO)).isEqualTo(1);
    }

    @Test
    void aProfileWithoutNamesIsA400() {
        Cookie cookie = sessionCookie(signIn(MARIO, requestCode(MARIO)));

        assertThat(mvc.put().uri("/api/customer/profile").cookie(cookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": \" \", \"lastName\": \"Rossi\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
