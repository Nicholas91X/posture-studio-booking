package com.posturestudio.booking.payment;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

// Rows for the payment tests, inserted with plain SQL
final class PaymentTestData {

    private final JdbcTemplate jdbc;
    private int bookings;

    PaymentTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long practitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }

    // a confirmed booking from the panel (the simplest valid row), each one an hour after the previous
    long booking(long practitionerId) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class,
                "customer" + practitionerId + "-" + bookings + "@example.com");
        Long customerId = jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
        Long serviceId = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, practitionerId);
        OffsetDateTime start = OffsetDateTime.parse("2026-11-20T09:00+01:00").plusHours(bookings++);
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents)
                values (?, ?, ?, ?, ?, 'CONFIRMED', 'PRACTITIONER', 'IN_STUDIO', 3000)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, start, start.plusHours(1));
    }

    long payment(long bookingId, String account, String status, String checkoutSessionId) {
        return jdbc.queryForObject("""
                insert into payment (booking_id, account, amount_cents, status, stripe_checkout_session_id)
                values (?, ?, 3000, ?, ?)
                returning id
                """, Long.class, bookingId, account, status, checkoutSessionId);
    }

    void assign(long practitionerId, String account) {
        jdbc.update("insert into practitioner_payment_account (practitioner_id, account, assigned_at) values (?, ?, now())",
                practitionerId, account);
    }
}
