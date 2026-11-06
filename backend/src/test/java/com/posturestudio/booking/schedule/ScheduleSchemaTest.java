package com.posturestudio.booking.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// Tests the constraints of V3 on schedule versions, working bands and absences with plain SQL.
// Days: 1 = Monday, 2 = Tuesday (java.time.DayOfWeek)
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class ScheduleSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private long anna;

    @BeforeEach
    void insertAnna() {
        anna = insertPractitioner("anna@example.com");
    }

    // ***** working_schedule *****

    @Test
    void versionWithMorningAndAfternoonBandsIsAccepted() {
        long version = insertVersion(anna, "2026-11-01");

        insertBand(version, 1, "09:00", "13:00");
        insertBand(version, 1, "14:00", "19:00");

        Integer bands = jdbc.queryForObject(
                "select count(*) from working_hours where schedule_id = ?", Integer.class, version);
        assertThat(bands).isEqualTo(2);
    }

    @Test
    void twoVersionsOfTheSamePractitionerFromTheSameDayAreRejected() {
        insertVersion(anna, "2026-11-01");

        assertViolates("working_schedule_practitioner_valid_from_uk", () -> insertVersion(anna, "2026-11-01"));
    }

    @Test
    void twoPractitionersCanHaveVersionsFromTheSameDay() {
        long marco = insertPractitioner("marco@example.com");
        insertVersion(anna, "2026-11-01");

        Assertions.assertDoesNotThrow(() -> insertVersion(marco, "2026-11-01"));
    }

    @Test
    void versionOfMissingPractitionerIsRejected() {
        assertViolates("working_schedule_practitioner_fk", () -> insertVersion(999999, "2026-11-01"));
    }

    // ***** working_hours: one row *****

    // one violation per test: after a failed statement PostgreSQL aborts the test transaction,
    // and any further statement would fail for that reason instead
    @Test
    void dayOfWeekZeroIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_day_of_week_ck", () -> insertBand(version, 0, "09:00", "13:00"));
    }

    @Test
    void dayOfWeekEightIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_day_of_week_ck", () -> insertBand(version, 8, "09:00", "13:00"));
    }

    @Test
    void bandEndingBeforeItStartsIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_times_ck", () -> insertBand(version, 1, "13:00", "09:00"));
    }

    @Test
    void bandStartingAtAQuarterPastIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_start_time_ck", () -> insertBand(version, 1, "09:15", "13:00"));
    }

    @Test
    void bandEndingAtAQuarterToIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_end_time_ck", () -> insertBand(version, 1, "09:00", "12:45"));
    }

    @Test
    void bandWithSecondsIsRejected() {
        long version = insertVersion(anna, "2026-11-01");

        assertViolates("working_hours_start_time_ck", () -> insertBand(version, 1, "09:00:30", "13:00"));
    }

    @Test
    void bandOnTheHalfHourIsAccepted() {
        long version = insertVersion(anna, "2026-11-01");

        Assertions.assertDoesNotThrow(() -> insertBand(version, 1, "09:30", "12:30"));
    }

    // ***** working_hours: overlaps (exclusion constraint) *****

    @Test
    void overlappingBandsOnTheSameDayOfTheSameVersionAreRejected() {
        long version = insertVersion(anna, "2026-11-01");
        insertBand(version, 1, "09:00", "13:00");

        assertViolates("working_hours_no_overlap_ex", () -> insertBand(version, 1, "12:00", "15:00"));
    }

    @Test
    void bandInsideAnotherIsRejected() {
        long version = insertVersion(anna, "2026-11-01");
        insertBand(version, 1, "09:00", "13:00");

        assertViolates("working_hours_no_overlap_ex", () -> insertBand(version, 1, "10:00", "11:00"));
    }

    // the positive cases below catch a constraint written too wide: without them,
    // a constraint that rejected everything would still pass the two tests above

    @Test
    void touchingBandsAreAccepted() {
        long version = insertVersion(anna, "2026-11-01");
        insertBand(version, 1, "09:00", "13:00");

        // [09:00, 13:00) and [13:00, 14:00) do not overlap: the end is excluded
        Assertions.assertDoesNotThrow(() -> insertBand(version, 1, "13:00", "14:00"));
    }

    @Test
    void sameBandOnAnotherDayIsAccepted() {
        long version = insertVersion(anna, "2026-11-01");
        insertBand(version, 1, "09:00", "13:00");

        Assertions.assertDoesNotThrow(() -> insertBand(version, 2, "09:00", "13:00"));
    }

    @Test
    void sameBandInAnotherVersionIsAccepted() {
        long winter = insertVersion(anna, "2026-11-01");
        long summer = insertVersion(anna, "2027-06-01");
        insertBand(winter, 1, "09:00", "13:00");

        Assertions.assertDoesNotThrow(() -> insertBand(summer, 1, "09:00", "13:00"));
    }

    @Test
    void deletingAVersionDeletesItsBands() {
        long version = insertVersion(anna, "2026-11-01");
        insertBand(version, 1, "09:00", "13:00");
        insertBand(version, 2, "09:00", "13:00");

        jdbc.update("delete from working_schedule where id = ?", version);

        Integer bands = jdbc.queryForObject(
                "select count(*) from working_hours where schedule_id = ?", Integer.class, version);
        assertThat(bands).isZero();
    }

    // ***** unavailability *****

    @Test
    void validUnavailabilityIsAccepted() {
        Assertions.assertDoesNotThrow(
                () -> insertUnavailability(anna, "2026-11-09T11:15:00+01", "2026-11-09T12:00:00+01"));
    }

    @Test
    void unavailabilityEndingWhenItStartsIsRejected() {
        assertViolates("unavailability_times_ck",
                () -> insertUnavailability(anna, "2026-11-09T11:15:00+01", "2026-11-09T11:15:00+01"));
    }

    @Test
    void overlappingUnavailabilitiesAreAccepted() {
        insertUnavailability(anna, "2026-11-09T09:00:00+01", "2026-11-09T13:00:00+01");

        Assertions.assertDoesNotThrow(
                () -> insertUnavailability(anna, "2026-11-09T11:00:00+01", "2026-11-09T15:00:00+01"));
    }

    @Test
    void unavailabilityOfMissingPractitionerIsRejected() {
        assertViolates("unavailability_practitioner_fk",
                () -> insertUnavailability(999999, "2026-11-09T09:00:00+01", "2026-11-09T13:00:00+01"));
    }

    // ***** helpers *****

    private void assertViolates(String constraintName, Runnable statement) {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class, statement::run);
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

    private long insertVersion(long practitionerId, String validFrom) {
        return jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, ?::date) returning id",
                Long.class, practitionerId, validFrom);
    }

    private void insertBand(long scheduleId, int dayOfWeek, String start, String end) {
        jdbc.update(
                "insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, ?, ?::time, ?::time)",
                scheduleId, dayOfWeek, start, end);
    }

    private void insertUnavailability(long practitionerId, String startsAt, String endsAt) {
        jdbc.update(
                "insert into unavailability (practitioner_id, starts_at, ends_at) values (?, ?::timestamptz, ?::timestamptz)",
                practitionerId, startsAt, endsAt);
    }
}
