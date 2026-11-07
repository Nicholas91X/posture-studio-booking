package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

// Rows for the booking tests, inserted with plain SQL: no booking can be created from Java before piece 2.
// Times are written with the offset of Rome, as in the APIs (2026-11-09T09:00+01:00)
final class BookingTestData {

    // a hold or a deadline far enough in the future not to matter, when the test is not about it
    static final String LATER = "2026-11-07T10:00+01:00";

    private final JdbcTemplate jdbc;

    BookingTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long practitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }

    long customer(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }

    long service(long practitionerId, int durationMinutes) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', ?, 3000) returning id",
                Long.class, practitionerId, durationMinutes);
    }

    // Monday to Friday 9-13 and 14-19, from the given date
    void weekdays(long practitionerId, String validFrom) {
        Long scheduleId = jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, ?::date) returning id",
                Long.class, practitionerId, validFrom);
        for (int day = 1; day <= 5; day++) {
            jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, ?, '09:00', '13:00')",
                    scheduleId, day);
            jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, ?, '14:00', '19:00')",
                    scheduleId, day);
        }
    }

    // a booking with the columns its status requires: a hold for PENDING_PAYMENT, a deadline for PENDING_APPROVAL
    long booking(long practitionerId, long serviceId, long customerId, String startsAt, String endsAt, String status) {
        return booking(practitionerId, serviceId, customerId, startsAt, endsAt, status,
                status.equals("PENDING_PAYMENT") ? LATER : null);
    }

    long booking(long practitionerId, long serviceId, long customerId, String startsAt, String endsAt, String status,
                 String holdExpiresAt) {
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, hold_expires_at, approval_deadline)
                values (?, ?, ?, ?, ?, ?, 'CUSTOMER', 'CARD', 3000, ?, ?)
                returning id
                """, Long.class,
                customerId, practitionerId, serviceId, at(startsAt), at(endsAt), status,
                at(holdExpiresAt), status.equals("PENDING_APPROVAL") ? at(LATER) : null);
    }

    // a booking made by the practitioner from the panel, to be paid in the studio (D-024, D-040)
    long panelBooking(long practitionerId, long serviceId, long customerId, String startsAt, String endsAt) {
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents)
                values (?, ?, ?, ?, ?, 'CONFIRMED', 'PRACTITIONER', 'IN_STUDIO', 3000)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, at(startsAt), at(endsAt));
    }

    void absence(long practitionerId, String startsAt, String endsAt) {
        jdbc.update("insert into unavailability (practitioner_id, starts_at, ends_at) values (?, ?, ?)",
                practitionerId, at(startsAt), at(endsAt));
    }

    static OffsetDateTime at(String time) {
        return time == null ? null : OffsetDateTime.parse(time);
    }
}
