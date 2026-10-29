package com.posturestudio.booking.practitioner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.shared.NotFoundException;

// @DataJpaTest starts only JPA: services are not there, so PractitionerService is imported by hand
@DataJpaTest
@Import({TestcontainersConfiguration.class, PractitionerService.class})
public class PractitionerServiceTest {

    @Autowired
    PractitionerService service;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void settingsOfNewPractitionerAreTheDatabaseDefaults() {
        // arrange: inserted with SQL, so the values come from the defaults of V1, not from Java
        long practitionerId = insertPractitioner("anna@example.com");

        // act
        PractitionerSettings settings = service.getSettings(practitionerId);

        // assert
        assertThat(settings).isEqualTo(new PractitionerSettings(24, 90, 30));
    }

    @Test
    void updatedSettingsAreWrittenWithoutSave() {
        // arrange
        long practitionerId = insertPractitioner("anna@example.com");

        // act
        PractitionerSettings returned = service.updateSettings(practitionerId, new PractitionerSettings(48, 60, 15));
        // the service joins the test transaction, so there is no commit at its end:
        // flush() does what the commit would do. Without it, the row would still have the old values
        entityManager.flush();

        // assert: read the row, not the object in memory
        assertThat(returned).isEqualTo(new PractitionerSettings(48, 60, 15));
        assertThat(readRow(practitionerId)).isEqualTo(new PractitionerSettings(48, 60, 15));
    }

    @Test
    void updateChangesOnlyThatPractitioner() {
        // arrange
        long anna = insertPractitioner("anna@example.com");
        long marco = insertPractitioner("marco@example.com");

        // act
        service.updateSettings(anna, new PractitionerSettings(48, 60, 15));
        entityManager.flush();

        // assert
        assertThat(readRow(marco)).isEqualTo(new PractitionerSettings(24, 90, 30));
    }

    @Test
    void getSettingsOfMissingPractitionerThrowsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.getSettings(999999));
    }

    @Test
    void updateSettingsOfMissingPractitionerThrowsNotFound() {
        Assertions.assertThrows(NotFoundException.class,
                () -> service.updateSettings(999999, new PractitionerSettings(48, 60, 15)));
    }

    private PractitionerSettings readRow(long practitionerId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select cancellation_notice_hours, single_credit_validity_days, slot_step_minutes from practitioner where id = ?",
                practitionerId);
        return new PractitionerSettings(
                (Integer) row.get("cancellation_notice_hours"),
                (Integer) row.get("single_credit_validity_days"),
                (Integer) row.get("slot_step_minutes"));
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
