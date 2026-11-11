package com.posturestudio.booking.credit;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

// Rows for the credit tests, inserted with plain SQL: the schema is under test, not the Java code.
// created_at is written by Java in the application; here now() is enough
final class CreditTestData {

    private final JdbcTemplate jdbc;
    private int bookings;
    private int payments;

    CreditTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long practitioner(String email) {
        long userId = user("PRACTITIONER", email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }

    // the account of a practitioner: who grants, adjusts, extends (D-067)
    long userOf(long practitionerId) {
        return jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, practitionerId);
    }

    long customer(String email) {
        long userId = user("CUSTOMER", email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }

    long service(long practitionerId) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 3000) returning id",
                Long.class, practitionerId);
    }

    long packageOffer(long practitionerId, long serviceId) {
        return jdbc.queryForObject("""
                insert into package_offer (practitioner_id, service_id, name, sessions_count, price_cents, validity_days)
                values (?, ?, '5 sedute', 5, 12000, 90) returning id
                """, Long.class, practitionerId, serviceId);
    }

    // a package bought and paid (V15): the photo of what the customer saw is in the payment
    long packagePayment(long customerId, long packageOfferId) {
        int n = payments++;
        return jdbc.queryForObject("""
                insert into payment (purpose, package_offer_id, customer_id, package_credits, package_validity_days,
                                     account, amount_cents, status, stripe_checkout_session_id, checkout_url)
                values ('PACKAGE', ?, ?, 5, 90, 'alpha', 12000, 'SUCCEEDED', 'cs_test_pkg_' || ? || '_' || ?,
                        'https://checkout.stripe.test/c/pay/pkg')
                returning id
                """, Long.class, packageOfferId, customerId, customerId, n);
    }

    // a lot bought with a package, without movements
    long packageLot(long customerId, long practitionerId, long serviceId) {
        long offer = packageOffer(practitionerId, serviceId);
        long payment = packagePayment(customerId, offer);
        return jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, payment_id, package_offer_id,
                                        price_cents, valid_until, created_at)
                values (?, ?, ?, 'PACKAGE', ?, ?, 12000, '2027-02-08', now())
                returning id
                """, Long.class, customerId, practitionerId, serviceId, payment, offer);
    }

    // a gift of the practitioner, without movements
    long grantLot(long customerId, long practitionerId, long serviceId) {
        return jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, 'Seduta saltata per un nostro imprevisto', '2027-02-08', now())
                returning id
                """, Long.class, customerId, practitionerId, serviceId, userOf(practitionerId));
    }

    // a gift lot with its LOT_CREATED movement of n credits
    long lotWith(long customerId, long practitionerId, long serviceId, int credits) {
        long lot = grantLot(customerId, practitionerId, serviceId);
        lotCreated(lot, credits);
        return lot;
    }

    long lotCreated(long lotId, int credits) {
        return movement(lotId, credits, "LOT_CREATED", null, null, null);
    }

    long movement(long lotId, int amount, String reason, Long bookingId, Long createdBy, String note) {
        return jdbc.queryForObject("""
                insert into credit_movement (credit_lot_id, amount, reason, booking_id, created_by, note, created_at)
                values (?, ?, ?, ?, ?, ?, now())
                returning id
                """, Long.class, lotId, amount, reason, bookingId, createdBy, note);
    }

    long adjustment(long lotId, int amount, long createdBy) {
        return movement(lotId, amount, "ADJUSTMENT", null, createdBy, "Correzione di prova");
    }

    // a booking paid with a credit and confirmed, each one an hour after the previous
    long creditBooking(long practitionerId, long serviceId, long customerId) {
        OffsetDateTime start = OffsetDateTime.parse("2026-11-20T09:00+01:00").plusHours(bookings++);
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents)
                values (?, ?, ?, ?, ?, 'CONFIRMED', 'CUSTOMER', 'CREDIT', 3000)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, start, start.plusHours(1));
    }

    long balance(long lotId) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from credit_movement where credit_lot_id = ?",
                Long.class, lotId);
    }

    private long user(String role, String email) {
        return jdbc.queryForObject("insert into app_user (role, email) values (?, ?) returning id", Long.class,
                role, email);
    }
}
