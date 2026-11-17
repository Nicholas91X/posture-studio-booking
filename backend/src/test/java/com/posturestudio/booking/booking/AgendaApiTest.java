package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The agendas compared (D-091), from outside. Today is Friday 6 November 2026, 10:00 in Rome; the day looked at is
// Monday 9 November. Anna and Marco work 9-13 and 14-19 on weekdays. Marco has "Segreto Cliente" at 9:00-10:00 and a
// rejected request at 11:00; Anna has a session at 10:00-11:00 and is away in the afternoon, "dal dentista"
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class AgendaApiTest {

    private static final String MONDAY = "from=2026-11-09&to=2026-11-09";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;
    private long marcosBooking;

    @BeforeEach
    void aMondayOfAnnaAndMarco() {
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        data.weekdays(anna, "2026-11-02");
        data.weekdays(marco, "2026-11-02");
        long segreto = data.customer("segreto@example.com", "Segreto", "Cliente");
        marcosBooking = data.booking(marco, data.evaluation(marco), segreto,
                "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "CONFIRMED");
        data.booking(marco, data.service(marco, 60), segreto, "2026-11-09T11:00+01:00", "2026-11-09T12:00+01:00", "REJECTED");
        data.booking(anna, data.service(anna, 60), data.customer("cliente@example.com"),
                "2026-11-09T10:00+01:00", "2026-11-09T11:00+01:00", "CONFIRMED");
        data.absence(anna, "2026-11-09T14:00+01:00", "2026-11-09T19:00+01:00");
        jdbc.update("update unavailability set reason = 'Dal dentista' where practitioner_id = ?", anna);
    }

    // the privacy, field by field: of the others only who, busy, working hours and absences, each as start and end
    @Test
    void theAnswerHasOnlyTimesFieldByField() throws Exception {
        MvcTestResult result = mvc.get().uri("/api/panel/agendas?" + MONDAY).with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        String body = result.getResponse().getContentAsString();
        List<Map<String, Object>> agendas = JsonPath.read(body, "$");
        assertThat(agendas).isNotEmpty();
        for (Map<String, Object> agenda : agendas) {
            assertThat(agenda.keySet()).containsExactlyInAnyOrder("practitionerId", "firstName", "lastName", "busy",
                    "working", "absences");
            for (String list : List.of("busy", "working", "absences")) {
                List<Map<String, Object>> spans = JsonPath.read(agenda, "$." + list);
                spans.forEach(span -> assertThat(span.keySet()).containsExactlyInAnyOrder("start", "end"));
            }
        }
        // nothing of Marco's customer, of the booking, of its service; nothing of Anna's absence but its times
        assertThat(body).doesNotContain("Segreto", "segreto@example.com", "cliente@example.com", "Dal dentista",
                "\"id\"", "serviceId", "status", "CONFIRMED", "evaluation");
        assertThat(body).doesNotContain(":" + marcosBooking + ",");
    }

    @Test
    void marcoIsBusyOnlyWithWhatHoldsTheTime() {
        assertThat(mvc.get().uri("/api/panel/agendas?" + MONDAY).with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$[?(@.practitionerId == " + marco + ")].busy[*]").isEqualTo(List.of(
                        Map.of("start", "2026-11-09T09:00:00+01:00", "end", "2026-11-09T10:00:00+01:00")));
    }

    @Test
    void workingHoursAndAbsencesAreTimesOfTheDay() {
        MvcTestResult result = mvc.get().uri("/api/panel/agendas?" + MONDAY).with(sessions.practitioner(marco)).exchange();

        assertThat(result).bodyJson().extractingPath("$[?(@.practitionerId == " + anna + ")].working[*]").isEqualTo(List.of(
                Map.of("start", "2026-11-09T09:00:00+01:00", "end", "2026-11-09T13:00:00+01:00"),
                Map.of("start", "2026-11-09T14:00:00+01:00", "end", "2026-11-09T19:00:00+01:00")));
        assertThat(result).bodyJson().extractingPath("$[?(@.practitionerId == " + anna + ")].absences[*]").isEqualTo(List.of(
                Map.of("start", "2026-11-09T14:00:00+01:00", "end", "2026-11-09T19:00:00+01:00")));
    }

    // Anna: 9-10 and 11-13 (10-11 booked, away in the afternoon); Marco: 10-13 and 14-19. Together: 11-13 only.
    // Two hours exactly fit; two hours and a minute do not
    @Test
    void freeTogetherAtTheEdges() {
        assertThat(freeTogether(60)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                [{"start": "2026-11-09T11:00:00+01:00", "end": "2026-11-09T13:00:00+01:00"}]""");
        assertThat(freeTogether(120)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                [{"start": "2026-11-09T11:00:00+01:00", "end": "2026-11-09T13:00:00+01:00"}]""");
        assertThat(freeTogether(121)).hasStatusOk().bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void aPractitionerWhoIsNotActiveIsNotFound() {
        assertThat(mvc.get().uri("/api/panel/agendas/free-together?" + MONDAY + "&durationMinutes=60&practitionerIds="
                + anna + "&practitionerIds=999999").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void moreThan31DaysIs400OnThePeriod() {
        assertThat(mvc.get().uri("/api/panel/agendas?from=2026-11-01&to=2026-12-15").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("period");
    }

    @Test
    void onlyPractitionersSeeTheAgendas() {
        Long customerUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'curiosa@example.com') returning id", Long.class);

        assertThat(mvc.get().uri("/api/panel/agendas?" + MONDAY).with(sessions.user(customerUser)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/panel/agendas?" + MONDAY)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult freeTogether(int minutes) {
        return mvc.get().uri("/api/panel/agendas/free-together?" + MONDAY + "&durationMinutes=" + minutes
                + "&practitionerIds=" + anna + "&practitionerIds=" + marco)
                .with(sessions.practitioner(anna)).exchange();
    }
}
