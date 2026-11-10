package com.posturestudio.booking.practitioner;

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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class PractitionerPanelApiTest {

    // the database defaults after V4
    private static final String DEFAULTS = """
            {"cancellationNoticeHours": 24, "singleCreditValidityDays": 90, "slotStepMinutes": 60,
             "minBookingNoticeHours": 24, "bookingHorizonDays": 21,
             "maxFutureBookings": 4, "bookingApproval": "MANUAL", "approvalWindowHours": 12}
            """;

    private static final String CHANGED = """
            {"cancellationNoticeHours": 48, "singleCreditValidityDays": 60, "slotStepMinutes": 30,
             "minBookingNoticeHours": 48, "bookingHorizonDays": 14,
             "maxFutureBookings": 6, "bookingApproval": "AUTOMATIC", "approvalWindowHours": 24}
            """;

    @Autowired
    MockMvcTester mvc;

    // signed-in requests: a real session and a CSRF token (guide 25, part 8)
    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private String anna;
    private long marco;

    @BeforeEach
    void insertPractitioners() {
        anna = String.valueOf(insertPractitioner("anna@example.com"));
        marco = insertPractitioner("marco@example.com");
    }

    @Test
    void settingsAreReadAndChanged() {
        // read: the defaults of the database
        assertThat(mvc.get().uri("/api/panel/settings").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo(DEFAULTS);

        // change
        assertThat(updateSettings(anna, CHANGED))
                .hasStatusOk()
                .bodyJson().extractingPath("$.cancellationNoticeHours").isEqualTo(48);

        // read again
        assertThat(mvc.get().uri("/api/panel/settings").with(sessions.practitioner(anna)))
                .bodyJson().isStrictlyEqualTo(CHANGED);
    }

    @Test
    void invalidSettingAnswers400WithTheField() {
        MvcTestResult result = updateSettings(anna, CHANGED.replace("\"cancellationNoticeHours\": 48", "\"cancellationNoticeHours\": 0"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("cancellationNoticeHours");
    }

    @Test
    void slotStepOtherThan30Or60Answers400OnItsField() {
        MvcTestResult result = updateSettings(anna, DEFAULTS.replace("\"slotStepMinutes\": 60", "\"slotStepMinutes\": 45"));

        // 400 from validation, not 409 from the database check
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("slotStepMinutes");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("OneOf");
    }

    @Test
    void missingNewSettingsAnswer400OnTheirFields() {
        // a client still sending the five settings of step 6
        MvcTestResult result = updateSettings(anna, """
                {"cancellationNoticeHours": 24, "singleCreditValidityDays": 90, "slotStepMinutes": 60,
                 "minBookingNoticeHours": 24, "bookingHorizonDays": 21}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("maxFutureBookings", "bookingApproval", "approvalWindowHours");
    }

    @Test
    void approvalWindowLongerThanTheNoticeAnswers400OnTheWindow() {
        // the practitioner lowers the notice to 6 hours and keeps the 12-hour window
        MvcTestResult result = updateSettings(anna, DEFAULTS.replace("\"minBookingNoticeHours\": 24", "\"minBookingNoticeHours\": 6"));

        // the class-level constraint, reported on the window whichever of the two changed;
        // 400 from validation, not 409 from practitioner_approval_window_hours_ck
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("approvalWindowHours");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("WindowWithinNotice");
    }

    @Test
    void approvalWindowOver48HoursAnswers400EvenWithALongNotice() {
        MvcTestResult result = updateSettings(anna, DEFAULTS
                .replace("\"minBookingNoticeHours\": 24", "\"minBookingNoticeHours\": 200")
                .replace("\"approvalWindowHours\": 12", "\"approvalWindowHours\": 49"));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("approvalWindowHours");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("Max");
    }

    @Test
    void unknownApprovalModeAnswers400() {
        // a generic 400 from the JSON reading, without the field: accepted (D-042)
        MvcTestResult result = updateSettings(anna, DEFAULTS.replace("\"MANUAL\"", "\"MAYBE\""));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        Integer annasWindow = jdbc.queryForObject(
                "select approval_window_hours from practitioner where id = ?", Integer.class, Long.parseLong(anna));
        assertThat(annasWindow).isEqualTo(12);
    }

    @Test
    void changingOwnSettingsLeavesTheOtherPractitionerUntouched() {
        updateSettings(anna, CHANGED);

        Integer marcosNotice = jdbc.queryForObject(
                "select cancellation_notice_hours from practitioner where id = ?", Integer.class, marco);
        assertThat(marcosNotice).isEqualTo(24);
    }

    @Test
    void withoutASessionTheAnswerIs401() {
        assertThat(mvc.get().uri("/api/panel/settings")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult updateSettings(String practitioner, String json) {
        return mvc.put().uri("/api/panel/settings").with(sessions.practitioner(practitioner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
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
