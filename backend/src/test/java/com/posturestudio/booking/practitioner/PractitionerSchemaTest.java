package com.posturestudio.booking.practitioner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// Tests the settings constraints of V1, V3 and V4 with plain SQL: it is the database that defends itself.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class PractitionerSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void newPractitionerGetsTheDefaultsOfV3() {
        long practitionerId = insertPractitioner("anna@example.com");

        Map<String, Object> row = jdbc.queryForMap(
                "select slot_step_minutes, min_booking_notice_hours, booking_horizon_days from practitioner where id = ?",
                practitionerId);
        assertThat(row).containsEntry("slot_step_minutes", 60)
                .containsEntry("min_booking_notice_hours", 24)
                .containsEntry("booking_horizon_days", 21);
    }

    @Test
    void slotStepOf30IsAccepted() {
        long practitionerId = insertPractitioner("anna@example.com");

        int updated = jdbc.update("update practitioner set slot_step_minutes = 30 where id = ?", practitionerId);

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void slotStepOf45IsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_slot_step_minutes_ck",
                () -> jdbc.update("update practitioner set slot_step_minutes = 45 where id = ?", practitionerId));
    }

    @Test
    void zeroMinBookingNoticeIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        // Since V4 a notice of 0 breaks two checks: its own and the approval window one (window >= 1 and
        // window <= notice cannot both hold). PostgreSQL reports only the first one it evaluates, so either
        // name is a correct answer: the point is that the row is rejected
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update practitioner set min_booking_notice_hours = 0 where id = ?", practitionerId));
        assertThat(exception.getMessage()).containsAnyOf(
                "practitioner_min_booking_notice_hours_ck", "practitioner_approval_window_hours_ck");
    }

    @Test
    void zeroBookingHorizonIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_booking_horizon_days_ck",
                () -> jdbc.update("update practitioner set booking_horizon_days = 0 where id = ?", practitionerId));
    }

    // ***** V4 *****

    @Test
    void newPractitionerGetsTheDefaultsOfV4() {
        long practitionerId = insertPractitioner("anna@example.com");

        Map<String, Object> row = jdbc.queryForMap(
                "select max_future_bookings, booking_approval, approval_window_hours from practitioner where id = ?",
                practitionerId);
        assertThat(row).containsEntry("max_future_bookings", 4)
                .containsEntry("booking_approval", "MANUAL")
                .containsEntry("approval_window_hours", 12);
    }

    @Test
    void zeroMaxFutureBookingsIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_max_future_bookings_ck",
                () -> jdbc.update("update practitioner set max_future_bookings = 0 where id = ?", practitionerId));
    }

    @Test
    void unknownApprovalModeIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_booking_approval_ck",
                () -> jdbc.update("update practitioner set booking_approval = 'MAYBE' where id = ?", practitionerId));
    }

    @Test
    void approvalWindowLongerThanTheNoticeIsRejected() {
        // a check on two columns of the same row: lowering the notice under the window is caught too
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_approval_window_hours_ck",
                () -> jdbc.update("update practitioner set min_booking_notice_hours = 6 where id = ?", practitionerId));
    }

    @Test
    void approvalWindowEqualToTheNoticeIsAccepted() {
        long practitionerId = insertPractitioner("anna@example.com");

        int updated = jdbc.update("update practitioner set approval_window_hours = 24 where id = ?", practitionerId);

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void approvalWindowOver48HoursIsRejectedEvenWithALongNotice() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_approval_window_hours_ck", () -> jdbc.update(
                "update practitioner set min_booking_notice_hours = 200, approval_window_hours = 49 where id = ?",
                practitionerId));
    }

    @Test
    void zeroApprovalWindowIsRejected() {
        long practitionerId = insertPractitioner("anna@example.com");

        assertViolates("practitioner_approval_window_hours_ck",
                () -> jdbc.update("update practitioner set approval_window_hours = 0 where id = ?", practitionerId));
    }

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
}
