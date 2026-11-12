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
                                     booked_by, paid_with, price_cents, free_cancellation_until)
                values (?, ?, ?, ?, ?, 'CONFIRMED', 'PRACTITIONER', 'IN_STUDIO', 3000, ?)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, start, start.plusHours(1),
                start.minusHours(24));
    }

    // a request of a customer waiting for its payment, held until 7 November (far after the fixed clock)
    long pendingBooking(long practitionerId, String customerEmail) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, customerEmail);
        Long customerId = jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
        Long serviceId = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, practitionerId);
        OffsetDateTime start = OffsetDateTime.parse("2026-11-20T09:00+01:00").plusHours(bookings++);
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, hold_expires_at, created_at,
                                     free_cancellation_until)
                values (?, ?, ?, ?, ?, 'PENDING_PAYMENT', 'CUSTOMER', 'CARD', 3000, '2026-11-07T10:00+01:00',
                        '2026-11-06T09:00:00Z', ?)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, start, start.plusHours(1),
                start.minusHours(24));
    }

    // a request paid and waiting for the practitioner until 6 November 22:00 in Rome, its amount held on
    // "account" with PaymentIntent pi_<booking id>
    long waitingBooking(long practitionerId, String customerEmail, String account) {
        long bookingId = pendingBooking(practitionerId, customerEmail);
        jdbc.update("update booking set status = 'PENDING_APPROVAL', approval_deadline = '2026-11-06T22:00+01:00' where id = ?",
                bookingId);
        jdbc.update("""
                insert into payment (purpose, booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url,
                                     stripe_payment_intent_id)
                values ('BOOKING', ?, ?, 3000, 'AUTHORIZED', 'cs_test_' || ?, 'https://checkout.stripe.test/c/pay/x', 'pi_' || ?)
                """, bookingId, account, bookingId, bookingId);
        return bookingId;
    }

    // a payment link of a session (V14): PENDING for a valid one, EXPIRED for one already invalidated
    long linkPayment(long bookingId, String account, String status, String checkoutSessionId) {
        return jdbc.queryForObject("""
                insert into payment (purpose, booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url,
                                     source)
                values ('BOOKING', ?, ?, 3000, ?, ?, 'https://checkout.stripe.test/c/pay/' || ?, 'LINK')
                returning id
                """, Long.class, bookingId, account, status, checkoutSessionId, checkoutSessionId);
    }

    long payment(long bookingId, String account, String status, String checkoutSessionId) {
        return jdbc.queryForObject("""
                insert into payment (purpose, booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url)
                values ('BOOKING', ?, ?, 3000, ?, ?, 'https://checkout.stripe.test/c/pay/' || ?)
                returning id
                """, Long.class, bookingId, account, status, checkoutSessionId, checkoutSessionId);
    }

    long customer(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }

    // a package of 5 sessions on a new service of the practitioner (V2)
    long packageOffer(long practitionerId) {
        Long serviceId = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, practitionerId);
        return jdbc.queryForObject("""
                insert into package_offer (practitioner_id, service_id, name, sessions_count, price_cents, validity_days)
                values (?, ?, '5 sedute', 5, 12000, 90) returning id
                """, Long.class, practitionerId, serviceId);
    }

    // A purchase of a package (V15): the photo of what the customer saw, 5 sessions for 120 euros, 90 days, on
    // "alpha". createdAt decides until when its page can be paid (an hour, guide 37, part 3)
    long packagePayment(long customerId, long packageOfferId, String status, String checkoutSessionId,
                        String createdAt) {
        return jdbc.queryForObject("""
                insert into payment (purpose, package_offer_id, customer_id, package_credits, package_validity_days,
                                     account, amount_cents, status, stripe_checkout_session_id, checkout_url, created_at)
                values ('PACKAGE', ?, ?, 5, 90, 'alpha', 12000, ?, ?, 'https://checkout.stripe.test/c/pay/' || ?,
                        ?::timestamptz)
                returning id
                """, Long.class, packageOfferId, customerId, status, checkoutSessionId, checkoutSessionId, createdAt);
    }

    void assign(long practitionerId, String account) {
        jdbc.update("insert into practitioner_payment_account (practitioner_id, account, assigned_at) values (?, ?, now())",
                practitionerId, account);
    }
}
