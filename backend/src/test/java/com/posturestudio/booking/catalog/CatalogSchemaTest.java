package com.posturestudio.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// Tests the constraints of V2 with plain SQL: no entities involved, it is the database that defends itself.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class CatalogSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void validServiceIsAcceptedWithDefaults() {
        // arrange + act
        long serviceId = insertService(insertPractitioner("anna@example.com"));

        // assert
        Boolean active = jdbc.queryForObject(
                "select active from service where id = ?", Boolean.class, serviceId);
        // the PostgreSQL driver maps timestamptz to OffsetDateTime
        OffsetDateTime createdAt = jdbc.queryForObject(
                "select created_at from service where id = ?", OffsetDateTime.class, serviceId);
        assertThat(active).isTrue();
        assertThat(createdAt).isNotNull();
    }

    @Test
    void serviceWithBlankNameIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("service_name_ck", () -> jdbc.update(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, '   ', 60, 3000)",
                practitionerId));
    }

    @Test
    void serviceWithZeroDurationIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("service_duration_minutes_ck", () -> jdbc.update(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 0, 3000)",
                practitionerId));
    }

    @Test
    void serviceWithZeroPriceIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("service_price_cents_ck", () -> jdbc.update(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 0)",
                practitionerId));
    }

    @Test
    void serviceOfMissingPractitionerIsRejected() {
        assertViolates("service_practitioner_fk", () -> jdbc.update(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (999999, 'Seduta', 60, 3000)"));
    }

    @Test
    void validPackageIsAccepted() {
        // arrange
        long practitionerId = insertPractitioner("anna@example.com");
        long serviceId = insertService(practitionerId);

        // act
        long packageId = insertPackage(practitionerId, serviceId, 5, 90);

        // assert
        Boolean active = jdbc.queryForObject(
                "select active from package_offer where id = ?", Boolean.class, packageId);
        assertThat(active).isTrue();
    }

    @Test
    void packageWithTwoSessionsIsAccepted() {
        // boundary value: catches a "> 2" written instead of ">= 2"
        long practitionerId = insertPractitioner("anna@example.com");
        long serviceId = insertService(practitionerId);

        long packageId = insertPackage(practitionerId, serviceId, 2, 90);

        assertThat(packageId).isPositive();
    }

    @Test
    void packageWithOneSessionIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");
        long serviceId = insertService(practitionerId);

        assertViolates("package_offer_sessions_count_ck",
                () -> insertPackage(practitionerId, serviceId, 1, 90));
    }

    @Test
    void packageWithZeroValidityIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");
        long serviceId = insertService(practitionerId);

        assertViolates("package_offer_validity_days_ck",
                () -> insertPackage(practitionerId, serviceId, 5, 0));
    }

    @Test
    void packageOnServiceOfAnotherPractitionerIsRejected() {
        // arrange: both practitioners and the service exist, so only the composite key can fail
        long anna = insertPractitioner("anna@example.com");
        long marco = insertPractitioner("marco@example.com");
        long annasService = insertService(anna);

        // act + assert
        assertViolates("package_offer_service_practitioner_fk",
                () -> insertPackage(marco, annasService, 5, 90));
    }

    private void assertViolates(String constraintName, Runnable insert) {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class, insert::run);
        assertThat(exception.getMessage()).contains(constraintName);
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }

    private long insertService(long practitionerId) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta posturale', 60, 3000) returning id",
                Long.class, practitionerId);
    }

    private long insertPackage(long practitionerId, long serviceId, int sessionsCount, int validityDays) {
        return jdbc.queryForObject(
                "insert into package_offer (practitioner_id, service_id, name, sessions_count, price_cents, validity_days) values (?, ?, 'Pacchetto', ?, 15000, ?) returning id",
                Long.class, practitionerId, serviceId, sessionsCount, validityDays);
    }
}
