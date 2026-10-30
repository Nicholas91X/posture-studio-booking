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

import com.posturestudio.booking.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class PractitionerPanelApiTest {

    @Autowired
    MockMvcTester mvc;

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
        assertThat(mvc.get().uri("/api/panel/settings").header(DevHeaderCurrentPractitioner.HEADER, anna))
                .hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"cancellationNoticeHours": 24, "singleCreditValidityDays": 90, "slotStepMinutes": 30}
                        """);

        // change
        assertThat(updateSettings(anna, """
                {"cancellationNoticeHours": 48, "singleCreditValidityDays": 60, "slotStepMinutes": 15}
                """))
                .hasStatusOk()
                .bodyJson().extractingPath("$.cancellationNoticeHours").isEqualTo(48);

        // read again
        assertThat(mvc.get().uri("/api/panel/settings").header(DevHeaderCurrentPractitioner.HEADER, anna))
                .bodyJson().extractingPath("$.slotStepMinutes").isEqualTo(15);
    }

    @Test
    void invalidSettingAnswers400WithTheField() {
        MvcTestResult result = updateSettings(anna, """
                {"cancellationNoticeHours": 0, "singleCreditValidityDays": 60, "slotStepMinutes": 15}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("cancellationNoticeHours");
    }

    @Test
    void changingOwnSettingsLeavesTheOtherPractitionerUntouched() {
        updateSettings(anna, """
                {"cancellationNoticeHours": 48, "singleCreditValidityDays": 60, "slotStepMinutes": 15}
                """);

        Integer marcosNotice = jdbc.queryForObject(
                "select cancellation_notice_hours from practitioner where id = ?", Integer.class, marco);
        assertThat(marcosNotice).isEqualTo(24);
    }

    @Test
    void withoutHeaderTheAnswerIs401() {
        assertThat(mvc.get().uri("/api/panel/settings")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult updateSettings(String practitioner, String json) {
        return mvc.put().uri("/api/panel/settings").header(DevHeaderCurrentPractitioner.HEADER, practitioner)
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
