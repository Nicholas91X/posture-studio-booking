package com.posturestudio.booking.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

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
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The panel API of the schedule module from the outside. Today is Friday 6 November 2026,
// 10:00 in Rome (FixedClockConfiguration)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class SchedulePanelApiTest {


    private static final String WEEKDAYS_FROM_MONDAY = """
            {"validFrom": "2026-11-09", "bands": [
                {"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "13:00"},
                {"dayOfWeek": "MONDAY", "startTime": "14:00", "endTime": "19:00"}
            ]}
            """;

    @Autowired
    MockMvcTester mvc;

    // signed-in requests: a real session and a CSRF token (guide 25, part 8)
    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private String anna;
    private String marco;

    @BeforeEach
    void insertPractitioners() {
        anna = String.valueOf(insertPractitioner("anna@example.com"));
        marco = String.valueOf(insertPractitioner("marco@example.com"));
    }

    // ***** SCHEDULE VERSIONS *****

    @Test
    void createdVersionAnswers201WithLocationAndItsJson() {
        MvcTestResult result = post("/api/panel/schedules", anna, WEEKDAYS_FROM_MONDAY);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long id = idOf(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/panel/schedules/" + id);
        // days by name, dates and times in ISO, the two flags computed by the backend
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": %d, "validFrom": "2026-11-09", "current": false, "editable": true, "bands": [
                    {"dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "13:00:00"},
                    {"dayOfWeek": "MONDAY", "startTime": "14:00:00", "endTime": "19:00:00"}
                ]}
                """.formatted(id));
    }

    @Test
    void listShowsTheVersionsOfThePractitionerWhoActs() {
        post("/api/panel/schedules", anna, WEEKDAYS_FROM_MONDAY);

        assertThat(mvc.get().uri("/api/panel/schedules").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].validFrom").asArray().containsExactly("2026-11-09");
        assertThat(mvc.get().uri("/api/panel/schedules").with(sessions.practitioner(marco)))
                .hasStatusOk()
                .bodyJson().extractingPath("$").asArray().isEmpty();
    }

    @Test
    void bandAtAQuarterPastAnswers400OnItsTime() {
        MvcTestResult result = post("/api/panel/schedules", anna, """
                {"validFrom": "2026-11-09", "bands": [
                    {"dayOfWeek": "MONDAY", "startTime": "09:15", "endTime": "13:00"}
                ]}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("bands[0].startTime");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("OnTheHalfHour");
    }

    @Test
    void overlappingBandsAnswer400OnTheLaterBand() {
        MvcTestResult result = post("/api/panel/schedules", anna, """
                {"validFrom": "2026-11-09", "bands": [
                    {"dayOfWeek": "MONDAY", "startTime": "09:00", "endTime": "13:00"},
                    {"dayOfWeek": "MONDAY", "startTime": "12:00", "endTime": "15:00"}
                ]}
                """);

        // 400 from validation: not the 409 DATA_CONFLICT of the exclusion constraint
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("bands[1]");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("ValidBands");
    }

    @Test
    void versionInThePastAnswers409WithItsCode() {
        MvcTestResult result = post("/api/panel/schedules", anna, """
                {"validFrom": "2026-11-05", "bands": []}
                """);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SCHEDULE_STARTS_IN_THE_PAST");
    }

    @Test
    void startedVersionAnswers409OnReplaceAndDelete() {
        long started = insertVersion(anna, "2026-11-01");

        assertThat(mvc.put().uri("/api/panel/schedules/" + started).with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON).content(WEEKDAYS_FROM_MONDAY))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("SCHEDULE_ALREADY_STARTED");
        assertThat(mvc.delete().uri("/api/panel/schedules/" + started).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void futureVersionIsReplacedAndDeleted() {
        MvcTestResult created = post("/api/panel/schedules", anna, WEEKDAYS_FROM_MONDAY);
        long id = idOf(created);

        assertThat(mvc.put().uri("/api/panel/schedules/" + id).with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON).content(WEEKDAYS_FROM_MONDAY))
                .hasStatusOk();
        assertThat(mvc.delete().uri("/api/panel/schedules/" + id).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void someoneElsesVersionAnswers404() {
        MvcTestResult created = post("/api/panel/schedules", marco, WEEKDAYS_FROM_MONDAY);
        long id = idOf(created);

        assertThat(mvc.delete().uri("/api/panel/schedules/" + id).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutASessionTheAnswerIs401() {
        assertThat(mvc.get().uri("/api/panel/schedules")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ***** UNAVAILABILITIES *****

    @Test
    void absenceIsCreatedAndListedInRomeTime() {
        // sent in UTC, shown back with the offset of Rome: the same instant
        MvcTestResult result = post("/api/panel/unavailabilities", anna, """
                {"startsAt": "2026-11-12T10:15:00Z", "endsAt": "2026-11-12T11:00:00Z", "reason": "Dentista"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri("/api/panel/unavailabilities").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        [{"startsAt": "2026-11-12T11:15:00+01:00", "endsAt": "2026-11-12T12:00:00+01:00",
                          "reason": "Dentista"}]
                        """);
    }

    @Test
    void absenceEndingBeforeItStartsAnswers400OnEndsAt() {
        MvcTestResult result = post("/api/panel/unavailabilities", anna, """
                {"startsAt": "2026-11-12T12:00:00+01:00", "endsAt": "2026-11-12T11:15:00+01:00"}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("endsAt");
    }

    @Test
    void reasonLongerThan200CharactersAnswers400() {
        MvcTestResult result = post("/api/panel/unavailabilities", anna, """
                {"startsAt": "2026-11-12T11:15:00+01:00", "endsAt": "2026-11-12T12:00:00+01:00", "reason": "%s"}
                """.formatted("x".repeat(201)));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("reason");
    }

    @Test
    void absenceIsDeletedOnlyByItsOwner() {
        MvcTestResult created = post("/api/panel/unavailabilities", anna, """
                {"startsAt": "2026-11-12T11:15:00+01:00", "endsAt": "2026-11-12T12:00:00+01:00"}
                """);
        long id = idOf(created);

        assertThat(mvc.delete().uri("/api/panel/unavailabilities/" + id).with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.delete().uri("/api/panel/unavailabilities/" + id).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    // ***** helpers *****

    private static long idOf(MvcTestResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return JsonPath.<Number>read(body, "$.id").longValue();
    }

    private MvcTestResult post(String uri, String practitioner, String json) {
        return mvc.post().uri(uri).with(sessions.practitioner(practitioner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private long insertVersion(String practitionerId, String validFrom) {
        return jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, ?::date) returning id",
                Long.class, Long.parseLong(practitionerId), validFrom);
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
