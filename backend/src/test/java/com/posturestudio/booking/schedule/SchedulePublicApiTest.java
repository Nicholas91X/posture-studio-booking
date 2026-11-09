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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// The public API of the free slots from the outside, with no session: public APIs
// do not ask who you are. Today is Friday 6 November 2026, 10:00 in Rome. Same configuration
// as SchedulePanelApiTest: same Spring context, same container
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class SchedulePublicApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long seduta;

    @BeforeEach
    void annaWorksOnMondayMorning() {
        anna = insertPractitioner("anna@example.com");
        seduta = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, anna);
        long version = jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, '2026-11-01') returning id",
                Long.class, anna);
        jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, 1, '09:00', '11:00')",
                version);
    }

    @Test
    void freeSlotsHaveExactlyStartAndEndInRomeTime() {
        assertThat(slots("from=2026-11-09&to=2026-11-09"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        [
                          {"start": "2026-11-09T09:00:00+01:00", "end": "2026-11-09T10:00:00+01:00"},
                          {"start": "2026-11-09T10:00:00+01:00", "end": "2026-11-09T11:00:00+01:00"}
                        ]
                        """);
    }

    @Test
    void reasonOfAnAbsenceNeverComesOut() {
        jdbc.update("""
                insert into unavailability (practitioner_id, starts_at, ends_at, reason)
                values (?, '2026-11-09T09:00:00+01', '2026-11-09T10:00:00+01', 'Visita medica riservata')
                """, anna);

        MvcTestResult result = slots("from=2026-11-09&to=2026-11-09");

        assertThat(result).hasStatusOk()
                .bodyJson().extractingPath("$[*].start").asArray().containsExactly("2026-11-09T10:00:00+01:00");
        assertThat(body(result)).doesNotContain("Visita").doesNotContain("reason");
    }

    @Test
    void weekBeyondTheHorizonIsAnEmptyList() {
        assertThat(slots("from=2026-12-07&to=2026-12-13"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void fromAfterToAnswers400OnTo() {
        MvcTestResult result = slots("from=2026-11-15&to=2026-11-09");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("to");
    }

    @Test
    void missingFromAnswers400OnFrom() {
        MvcTestResult result = slots("to=2026-11-09");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("from");
    }

    @Test
    void dateThatIsNotADateAnswers400() {
        assertThat(slots("from=domani&to=2026-11-09")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void serviceNotOnSaleAnswers404() {
        jdbc.update("update service set active = false where id = ?", seduta);

        assertThat(slots("from=2026-11-09&to=2026-11-09")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void deactivatedPractitionerAnswers404() {
        jdbc.update("update app_user set active = false where id = (select user_id from practitioner where id = ?)", anna);

        assertThat(slots("from=2026-11-09&to=2026-11-09")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** helpers *****

    private MvcTestResult slots(String query) {
        return mvc.get()
                .uri("/api/public/practitioners/" + anna + "/services/" + seduta + "/slots?" + query)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
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
