package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The admin API of the payment accounts (guide 30, part 9), from outside: real sessions, CSRF, the whole
// chain as in production. The configured accounts are "alpha" and "beta" (test configuration)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class PaymentAccountAdminApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long admin;
    private long anna;

    @BeforeEach
    void setUp() {
        admin = jdbc.queryForObject("insert into app_user (role, email) values ('ADMIN', 'admin@example.com') returning id",
                Long.class);
        anna = new PaymentTestData(jdbc).practitioner("anna@example.com");
    }

    @Test
    void theAdminAssignsAnAccountAndReadsItBack() {
        MvcTestResult assigned = assign(anna, "alpha");

        assertThat(assigned).hasStatusOk();
        assertThat(assigned).bodyJson().extractingPath("$.account").isEqualTo("alpha");
        assertThat(assigned).bodyJson().extractingPath("$.assignedAt").isNotNull();
        assertThat(mvc.get().uri("/api/admin/practitioners/" + anna + "/payment-account").with(sessions.user(admin)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.account").isEqualTo("alpha");
    }

    @Test
    void theListShowsTheConfiguredAccountsWithTheirPractitionersAndNoKey() throws Exception {
        assign(anna, "beta");

        MvcTestResult result = mvc.get().uri("/api/admin/payment-accounts").with(sessions.user(admin)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [{"name": "alpha", "practitionerIds": []}, {"name": "beta", "practitionerIds": [%d]}]
                """.formatted(anna));
        // not even a piece of a key or of a webhook secret
        assertThat(result.getResponse().getContentAsString()).doesNotContain("rk_", "whsec_");
    }

    @Test
    void reassigningIsAPutOfTheNewName() {
        assign(anna, "alpha");

        assertThat(assign(anna, "beta")).hasStatusOk().bodyJson().extractingPath("$.account").isEqualTo("beta");
        // read through the API: the row is in the same test transaction, not yet flushed for plain SQL
        assertThat(mvc.get().uri("/api/admin/practitioners/" + anna + "/payment-account").with(sessions.user(admin)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.account").isEqualTo("beta");
    }

    @Test
    void anAccountNotConfiguredIs409() {
        MvcTestResult result = assign(anna, "rossi");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_ACCOUNT_UNKNOWN");
    }

    @Test
    void aMalformedNameIs400() {
        assertThat(assign(anna, "Studio Rossi")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.put().uri("/api/admin/practitioners/" + anna + "/payment-account").with(sessions.user(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anUnknownPractitionerOrOneWithoutAccountIs404() {
        assertThat(assign(999_999, "alpha")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/admin/practitioners/999999/payment-account").with(sessions.user(admin)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/admin/practitioners/" + anna + "/payment-account").with(sessions.user(admin)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // only the admin: the rule of /api/admin/** (D-051)
    @Test
    void aPractitionerOrACustomerIsForbiddenAndAnonymousIsUnauthorized() {
        Long customerUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'carla@example.com') returning id", Long.class);

        assertThat(mvc.put().uri("/api/admin/practitioners/" + anna + "/payment-account").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON).content("{\"account\": \"alpha\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/admin/payment-accounts").with(sessions.user(customerUser)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/admin/payment-accounts")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult assign(long practitionerId, String account) {
        return mvc.put().uri("/api/admin/practitioners/" + practitionerId + "/payment-account").with(sessions.user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\": \"%s\"}".formatted(account))
                .exchange();
    }
}
