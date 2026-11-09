package com.posturestudio.booking.practitioner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The admin API (guide 26), from outside: real sessions, CSRF, the whole chain as in production
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class PractitionerAdminApiTest {

    // the database defaults after V4
    private static final String DEFAULTS = """
            {"cancellationNoticeHours": 24, "singleCreditValidityDays": 90, "slotStepMinutes": 60,
             "minBookingNoticeHours": 24, "bookingHorizonDays": 21,
             "maxFutureBookings": 4, "bookingApproval": "MANUAL", "approvalWindowHours": 12}
            """;

    private static final String ANNA = """
            {"email": " Anna.Rossi@Example.COM ", "firstName": " Anna ", "lastName": "Rossi"}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long admin;

    @BeforeEach
    void insertAdmin() {
        admin = jdbc.queryForObject("insert into app_user (role, email) values ('ADMIN', 'admin@example.com') returning id",
                Long.class);
    }

    // ***** creation *****

    @Test
    void creationWritesTheAccountAndThePractitionerTogether() {
        MvcTestResult result = create(ANNA);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long id = idOf(result);
        assertThat(result).hasHeader("Location", "/api/admin/practitioners/" + id);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": %d, "email": "anna.rossi@example.com", "firstName": "Anna", "lastName": "Rossi", "active": true}
                """.formatted(id));

        Map<String, Object> row = jdbc.queryForMap("""
                select u.email, u.role, u.active from practitioner p join app_user u on u.id = p.user_id where p.id = ?
                """, id);
        assertThat(row).containsEntry("email", "anna.rossi@example.com")
                .containsEntry("role", "PRACTITIONER")
                .containsEntry("active", true);
    }

    @Test
    void theNewPractitionerEntersThePanelWithTheDatabaseDefaults() {
        long id = idOf(create(ANNA));

        assertThat(mvc.get().uri("/api/panel/settings").with(sessions.practitioner(id)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo(DEFAULTS);
        // and it is on the public list at once, without working hours yet
        assertThat(mvc.get().uri("/api/public/practitioners"))
                .bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) id);
    }

    @Test
    void anEmailWithAnAccountIsRefusedWhateverItsRole() {
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'mario@example.com')");
        long marco = idOf(create("""
                {"email": "marco@example.com", "firstName": "Marco", "lastName": "Bianchi"}
                """));
        assertThat(mvc.post().uri("/api/admin/practitioners/" + marco + "/deactivate").with(sessions.user(admin)))
                .hasStatusOk();

        // a customer, a deactivated practitioner (reactivate, not create), the admin itself
        for (String email : new String[] {"mario@example.com", "Marco@example.com", "admin@example.com"}) {
            MvcTestResult result = create("""
                    {"email": "%s", "firstName": "Anna", "lastName": "Rossi"}
                    """.formatted(email));

            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_IN_USE");
        }
        assertThat(jdbc.queryForObject("select count(*) from practitioner", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from app_user where email = 'mario@example.com'", String.class))
                .isEqualTo("CUSTOMER");
    }

    @Test
    void anInvalidBodyAnswers400OnItsFields() {
        MvcTestResult result = create("""
                {"email": "mario@localhost", "firstName": " ", "lastName": "%s"}
                """.formatted("x".repeat(101)));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("email", "firstName", "lastName");
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(1);
    }

    // ***** list, read, rename *****

    @Test
    void theListHasEmailAndStateInNameOrder() {
        long rossi = idOf(create(ANNA));
        long bianchi = idOf(create("""
                {"email": "marco@example.com", "firstName": "Marco", "lastName": "Bianchi"}
                """));
        mvc.post().uri("/api/admin/practitioners/" + bianchi + "/deactivate").with(sessions.user(admin)).exchange();

        assertThat(mvc.get().uri("/api/admin/practitioners").with(sessions.user(admin)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        [{"id": %d, "email": "marco@example.com", "firstName": "Marco", "lastName": "Bianchi", "active": false},
                         {"id": %d, "email": "anna.rossi@example.com", "firstName": "Anna", "lastName": "Rossi", "active": true}]
                        """.formatted(bianchi, rossi));
    }

    @Test
    void renameChangesTheNamesTheCustomersSee() {
        long id = idOf(create(ANNA));

        assertThat(mvc.put().uri("/api/admin/practitioners/" + id).with(sessions.user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName": "Anna Maria", "lastName": "Rossi Bianchi"}
                        """))
                .hasStatusOk()
                .bodyJson().extractingPath("$.firstName").isEqualTo("Anna Maria");

        assertThat(mvc.get().uri("/api/public/practitioners"))
                .bodyJson().extractingPath("$[0].lastName").isEqualTo("Rossi Bianchi");
        // the email does not change with the names
        assertThat(mvc.get().uri("/api/admin/practitioners/" + id).with(sessions.user(admin)))
                .bodyJson().extractingPath("$.email").isEqualTo("anna.rossi@example.com");
    }

    @Test
    void anUnknownPractitionerIsNotFound() {
        long unknown = 999_999;

        assertThat(mvc.get().uri("/api/admin/practitioners/" + unknown).with(sessions.user(admin)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/admin/practitioners/" + unknown + "/deactivate").with(sessions.user(admin)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.put().uri("/api/admin/practitioners/" + unknown).with(sessions.user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName": "Anna", "lastName": "Rossi"}
                        """))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** deactivation (guide 26, part 6): one row, and everything follows *****

    @Test
    void aDeactivatedPractitionerIsOutEverywhereAtOnceAndComesBackWhenReactivated() {
        long id = idOf(create(ANNA));
        long service = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, id);
        var panelSession = sessions.practitioner(id);
        String slots = "/api/public/practitioners/" + id + "/services/" + service + "/slots?from=2026-11-09&to=2026-11-15";

        // before
        assertThat(mvc.get().uri("/api/panel/settings").with(panelSession)).hasStatusOk();
        assertThat(mvc.get().uri("/api/public/practitioners/" + id + "/catalog")).hasStatusOk();
        assertThat(mvc.get().uri(slots)).hasStatusOk();

        // deactivate, twice: idempotent
        for (int i = 0; i < 2; i++) {
            assertThat(mvc.post().uri("/api/admin/practitioners/" + id + "/deactivate").with(sessions.user(admin)))
                    .hasStatusOk()
                    .bodyJson().extractingPath("$.active").isEqualTo(false);
        }

        // after: the session already open is out at its next request (SessionAccounts), and nothing public
        assertThat(mvc.get().uri("/api/panel/settings").with(panelSession)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/public/practitioners")).bodyJson().isStrictlyEqualTo("[]");
        assertThat(mvc.get().uri("/api/public/practitioners/" + id + "/catalog")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri(slots)).hasStatus(HttpStatus.NOT_FOUND);

        // reactivate: everything comes back as it was; the old session is gone, a new sign-in works
        assertThat(mvc.post().uri("/api/admin/practitioners/" + id + "/activate").with(sessions.user(admin)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.active").isEqualTo(true);
        assertThat(mvc.get().uri("/api/public/practitioners/" + id + "/catalog")).hasStatusOk();
        assertThat(mvc.get().uri(slots)).hasStatusOk();
        assertThat(mvc.get().uri("/api/panel/settings").with(panelSession)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/panel/settings").with(sessions.practitioner(id))).hasStatusOk();
    }

    // ***** only the admin *****

    @Test
    void onlyTheAdminGetsIn() {
        long anna = idOf(create(ANNA));
        Long customer = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'mario@example.com') returning id", Long.class);

        assertThat(mvc.get().uri("/api/admin/practitioners")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/admin/practitioners").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(ANNA)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/admin/practitioners/" + anna + "/deactivate").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/api/admin/practitioners/" + anna + "/deactivate").with(sessions.user(customer)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select active from app_user where email = 'anna.rossi@example.com'", Boolean.class))
                .isTrue();
    }

    // ***** helpers *****

    private MvcTestResult create(String body) {
        return mvc.post().uri("/api/admin/practitioners").with(sessions.user(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    // the id of a practitioner just created; fails at once if the creation did not succeed
    private static long idOf(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return JsonPath.<Number>read(body, "$.id").longValue();
    }
}
