package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The update of V16 on rows that already exist (guide 42, part 11, point 7). Every other test starts from an
// empty database migrated to the last version, where that update touches nothing. Here: Flyway up to V15 only,
// bookings written as step 10 wrote them (no free_cancellation_until), then V16, then the check.
// A container of its own, without Spring: the other tests want the database at the last version, and this one
// needs it at V15 for a moment
class V16BackfillTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws SQLException {
        POSTGRES.start();
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() {
        dataSource.destroy();
        POSTGRES.stop();
    }

    @Test
    void existingBookingsGetTheNoticeOfTheirPractitioner() {
        migrateTo("15");
        long anna = practitioner("anna@example.com", 48);
        long marco = practitioner("marco@example.com", 24);
        long customer = customer("cliente@example.com");
        OffsetDateTime monday9 = OffsetDateTime.parse("2026-11-09T09:00+01:00");
        long annasBooking = booking(anna, customer, monday9, "CONFIRMED");
        long marcosBooking = booking(marco, customer, monday9, "WITHDRAWN");

        migrateTo("16");

        assertThat(freeUntil(annasBooking)).isEqualTo(monday9.minusHours(48).toInstant());
        assertThat(freeUntil(marcosBooking)).isEqualTo(monday9.minusHours(24).toInstant());
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_name = 'booking' and column_name = 'free_cancellation_until'
                """, String.class)).isEqualTo("NO");
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    // a practitioner with this notice of cancellation, and a service
    private long practitioner(String email, int noticeHours) {
        Long userId = jdbc.queryForObject("insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        long practitionerId = jdbc.queryForObject("""
                insert into practitioner (user_id, first_name, last_name, cancellation_notice_hours)
                values (?, 'Test', 'Practitioner', ?) returning id
                """, Long.class, userId, noticeHours);
        jdbc.update("insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000)",
                practitionerId);
        return practitionerId;
    }

    private long customer(String email) {
        Long userId = jdbc.queryForObject("insert into app_user (role, email) values ('CUSTOMER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject("insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }

    // a booking as step 10 wrote it: the columns of V15 only
    private long booking(long practitionerId, long customerId, OffsetDateTime start, String status) {
        long serviceId = jdbc.queryForObject("select id from service where practitioner_id = ?", Long.class,
                practitionerId);
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, cancelled_at)
                values (?, ?, ?, ?, ?, ?, 'PRACTITIONER', 'IN_STUDIO', 3000, ?)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, start, start.plusHours(1), status,
                status.equals("WITHDRAWN") ? start.minusDays(2) : null);
    }

    private java.time.Instant freeUntil(long bookingId) {
        return jdbc.queryForObject("select free_cancellation_until from booking where id = ?", OffsetDateTime.class,
                bookingId).toInstant();
    }
}
