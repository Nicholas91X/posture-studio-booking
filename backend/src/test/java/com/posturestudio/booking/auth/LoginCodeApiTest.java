package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// POST /api/public/auth/login-code from the outside (guide 24, part 9). Emails go to RecordingLoginCodeSender.
// @Transactional: MockMvc runs in the thread of the test, so the service joins its transaction, and
// everything is rolled back at the end
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, RecordingLoginCodeSender.Configuration.class})
@Transactional
class LoginCodeApiTest {

    private static final String URL = "/api/public/auth/login-code";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingLoginCodeSender sender;

    @BeforeEach
    void noEmailsYet() {
        sender.clear();
    }

    private MvcTestResult request(String email) {
        return request(email, "203.0.113.7");
    }

    private MvcTestResult request(String email, String ip) {
        return mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\"}")
                .with(from(ip))
                .exchange();
    }

    // the address of the client, as Tomcat would give it (behind Caddy: from X-Forwarded-For)
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // Nobody can tell from the answer who is a customer (guide 24, 5.1): same status, headers and body
    @Test
    void theAnswerIsTheSameForAKnownAnUnknownAndADeactivatedEmail() {
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'known@example.com')");
        jdbc.update("insert into app_user (role, email, active) values ('CUSTOMER', 'off@example.com', false)");

        MockHttpServletResponse known = request("known@example.com").getResponse();
        MockHttpServletResponse unknown = request("unknown@example.com").getResponse();
        MockHttpServletResponse deactivated = request("off@example.com").getResponse();

        for (MockHttpServletResponse response : List.of(known, unknown, deactivated)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.getHeaderNames()).isEqualTo(known.getHeaderNames());
        }
        // the difference is only in the emails, which only the owners of the mailboxes read
        assertThat(sender.sent()).extracting(RecordingLoginCodeSender.Sent::to)
                .containsExactly("known@example.com", "unknown@example.com", "off@example.com");
        assertThat(sender.sent().get(2).code()).isNull();
    }

    @Test
    void aMalformedEmailIsA400OnItsField() {
        assertThat(request("mario@localhost"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("email");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void beyondTheLimitTheAnswerIs429WithRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(request("mario@example.com")).hasStatus(HttpStatus.ACCEPTED);
        }

        // the clock is stopped: the first code leaves the 15-minute window in exactly 900 seconds
        assertThat(request("mario@example.com"))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader("Retry-After", "900");
    }

    @Test
    void theIpLimitCountsTheRealClient() {
        for (int i = 0; i < 30; i++) {
            assertThat(request("person" + i + "@example.com", "198.51.100.1")).hasStatus(HttpStatus.ACCEPTED);
        }

        assertThat(request("late@example.com", "198.51.100.1")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(request("late@example.com", "198.51.100.2")).hasStatus(HttpStatus.ACCEPTED);
    }

    @Test
    void aMailServerThatDoesNotAnswerIsA503() {
        sender.failNext();

        assertThat(request("mario@example.com")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
