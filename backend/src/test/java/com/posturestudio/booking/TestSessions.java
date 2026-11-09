package com.posturestudio.booking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.http.Cookie;

/**
 * Signed-in requests for the API tests (guide 25, part 8): a REAL session, written into Spring Session as
 * SessionController would write it, and its cookie. So every test goes through the whole chain as in
 * production: cookie, SessionAccounts (account active, role of now), rules by area. Plus a valid CSRF
 * token (csrf() of spring-security-test), needed by every request that changes something.
 *
 * A @Component in the test sources: @SpringBootTest scans the package of BookingApplication, test classes
 * included, so every API test can @Autowire it without importing a configuration (and without changing
 * the cached Spring contexts). @DataJpaTest does not scan components, and does not need it.
 */
@Component
public class TestSessions {

    // the names written by SessionAccounts (package-private there)
    private static final String USER_ID = "auth.userId";
    private static final String SIGNED_IN_AT = "auth.signedInAt";

    private final SessionRepository<?> repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    TestSessions(SessionRepository<?> repository, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // signed in as the account of this practitioner (the id of practitioner, not of app_user)
    public RequestPostProcessor practitioner(String practitionerId) {
        return practitioner(Long.parseLong(practitionerId));
    }

    public RequestPostProcessor practitioner(long practitionerId) {
        return user(jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, practitionerId));
    }

    // signed in as the account of this customer (the id of customer, not of app_user)
    public RequestPostProcessor customer(String customerId) {
        return customer(Long.parseLong(customerId));
    }

    public RequestPostProcessor customer(long customerId) {
        return user(jdbc.queryForObject("select user_id from customer where id = ?", Long.class, customerId));
    }

    // signed in as this account, now
    public RequestPostProcessor user(long userId) {
        return user(userId, clock.instant());
    }

    public RequestPostProcessor user(long userId, Instant signedInAt) {
        Cookie cookie = cookie(userId, signedInAt);
        return request -> {
            request.setCookies(cookie);
            return csrf().postProcessRequest(request);
        };
    }

    // the session cookie alone, without CSRF token
    public Cookie cookie(long userId, Instant signedInAt) {
        return new Cookie("SESSION", encode(open(repository, userId, signedInAt)));
    }

    // a type variable instead of the session class of Spring Session JDBC, which is not public
    private static <S extends Session> String open(SessionRepository<S> repository, long userId, Instant signedInAt) {
        S session = repository.createSession();
        session.setAttribute(USER_ID, userId);
        session.setAttribute(SIGNED_IN_AT, signedInAt);
        repository.save(session);
        return session.getId();
    }

    // Spring Session writes the id in the cookie in Base64 (DefaultCookieSerializer)
    private static String encode(String sessionId) {
        return Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    }
}
