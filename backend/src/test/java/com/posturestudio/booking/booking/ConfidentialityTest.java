package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

/**
 * "Riservatezza del calendario" (requisiti) and the areas by role (D-029), as tests: a "done" criterion of
 * step 8 (stato.md, guide 25, part 9). Two levels are proved: the rules of the filter chain (who enters
 * which area: 401 and 403) and the rules of the services (someone else's booking: 404, D-026).
 * Same configuration as BookingPanelApiTest: same Spring context. Today is Friday 6 November 2026
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class ConfidentialityTest {

    private static final String WEEK = "?from=2026-11-09&to=2026-11-15";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;
    private long annasService;
    private long mario;
    private long luigi;
    private long annaWithMario;
    private long marcoWithLuigi;
    private long admin;

    // Anna and Marco, practitioners; Mario and Luigi, customers. Anna has a request from Mario waiting,
    // Marco a confirmed session with Luigi
    @BeforeEach
    void twoPractitionersTwoCustomers() {
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        annasService = data.service(anna, 60);
        long marcosService = data.service(marco, 60);
        data.weekdays(anna, "2026-11-02");
        mario = data.customer("mario@example.com", "Mario", "Rossi");
        luigi = data.customer("luigi@example.com", "Luigi", "Verdi");
        annaWithMario = data.booking(anna, annasService, mario, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00",
                "PENDING_APPROVAL");
        marcoWithLuigi = data.booking(marco, marcosService, luigi, "2026-11-09T11:00+01:00", "2026-11-09T12:00+01:00",
                "CONFIRMED");
        admin = jdbc.queryForObject("insert into app_user (role, email) values ('ADMIN', 'admin@example.com') returning id",
                Long.class);
    }

    // ***** anonymous: only the free slots, never who booked nor the list of sessions *****

    @Test
    void anAnonymousVisitorSeesNoBookings() {
        for (String uri : new String[] {"/api/panel/bookings" + WEEK, "/api/panel/bookings/requests",
                "/api/panel/bookings/" + annaWithMario, "/api/customer/bookings", "/api/customer/bookings/" + annaWithMario}) {
            assertThat(mvc.get().uri(uri)).as(uri).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void anAnonymousVisitorSeesTheFreeSlotsAndNothingElse() throws Exception {
        var result = mvc.get().uri("/api/public/practitioners/" + anna + "/services/" + annasService
                + "/slots?from=2026-11-09&to=2026-11-09").exchange();

        assertThat(result).hasStatusOk();
        // only start and end: the 9:00 of Mario is simply not among them, with no trace of who took it
        assertThat(result).bodyJson().extractingPath("$[0]").asMap().containsOnlyKeys("start", "end");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Mario", "mario@", "09:00:00+01:00");
    }

    // ***** a practitioner sees only their own bookings *****

    @Test
    void aPractitionerCannotReadOrDecideTheBookingsOfTheOther() {
        assertThat(mvc.get().uri("/api/panel/bookings/" + annaWithMario).with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/panel/bookings/" + annaWithMario + "/approve").with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/panel/bookings/" + annaWithMario + "/reject").with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
        String status = jdbc.queryForObject("select status from booking where id = ?", String.class, annaWithMario);
        assertThat(status).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void theListsOfAPractitionerHaveOnlyTheirOwn() {
        assertThat(mvc.get().uri("/api/panel/bookings" + WEEK).with(sessions.practitioner(anna)))
                .hasStatusOk().bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) annaWithMario);
        assertThat(mvc.get().uri("/api/panel/bookings" + WEEK).with(sessions.practitioner(marco)))
                .hasStatusOk().bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) marcoWithLuigi);
        assertThat(mvc.get().uri("/api/panel/bookings/requests").with(sessions.practitioner(marco)))
                .hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
    }

    // ***** a customer sees only their own bookings *****

    @Test
    void aCustomerCannotReadOrWithdrawTheBookingsOfOthers() {
        assertThat(mvc.get().uri("/api/customer/bookings/" + annaWithMario).with(sessions.customer(luigi)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/customer/bookings/" + annaWithMario + "/withdraw").with(sessions.customer(luigi)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/customer/bookings").with(sessions.customer(luigi)))
                .hasStatusOk().bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) marcoWithLuigi);
    }

    // ***** every area only to its role (one role per user, D-021) *****

    @Test
    void aCustomerStaysOutOfThePanelAndOfTheAdmin() {
        assertThat(mvc.get().uri("/api/panel/bookings/requests").with(sessions.customer(mario))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/panel/settings").with(sessions.customer(mario))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/admin/practitioners").with(sessions.customer(mario))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void aPractitionerStaysOutOfTheCustomerAreaAndOfTheAdmin() {
        assertThat(mvc.get().uri("/api/customer/bookings").with(sessions.practitioner(anna))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/customer/profile").with(sessions.practitioner(anna))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/admin/practitioners").with(sessions.practitioner(anna))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void theAdminStaysOutOfThePanelAndOfTheCustomerArea() {
        assertThat(mvc.get().uri("/api/panel/bookings/requests").with(sessions.user(admin))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/customer/bookings").with(sessions.user(admin))).hasStatus(HttpStatus.FORBIDDEN);
        // its own area lets it in: nothing there yet (step 8 piece 3), so 404 and not 401 or 403
        assertThat(mvc.get().uri("/api/admin/practitioners").with(sessions.user(admin))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // denyAll (guide 25, 6.1): an address outside every area is closed to everyone, signed in or not
    @Test
    void anAddressOutsideTheAreasIsClosedToEveryone() {
        assertThat(mvc.get().uri("/api/bookings")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/bookings").with(sessions.customer(mario))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/bookings").with(sessions.practitioner(anna))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/bookings").with(sessions.user(admin))).hasStatus(HttpStatus.FORBIDDEN);
    }

    // ***** sessions already open, and CSRF *****

    @Test
    void aPractitionerDeactivatedWithAnOpenSessionIsOut() {
        var session = sessions.practitioner(anna);
        jdbc.update("update app_user set active = false where id = (select user_id from practitioner where id = ?)", anna);

        assertThat(mvc.get().uri("/api/panel/bookings/requests").with(session)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRequestThatChangesSomethingNeedsTheCsrfToken() {
        long annaUser = jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, anna);

        // a valid session cookie, but no token: what a form on another site could send (guide 25, 5.2)
        assertThat(mvc.post().uri("/api/panel/bookings/" + annaWithMario + "/approve")
                .cookie(sessions.cookie(annaUser, Instant.parse("2026-11-06T09:00:00Z"))))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("CSRF_TOKEN_INVALID");
        String status = jdbc.queryForObject("select status from booking where id = ?", String.class, annaWithMario);
        assertThat(status).isEqualTo("PENDING_APPROVAL");
    }
}
