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

    // with the payment account "alpha" of the test configuration (guide 31): its customers can pay by card
    long practitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, email);
        long practitionerId = jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
        jdbc.update("insert into practitioner_payment_account (practitioner_id, account, assigned_at) values (?, 'alpha', now())",
                practitionerId);
        return practitionerId;
    }

    long customer(String email) {
        return customer(email, "Test", "Customer");
    }

    long customer(String email, String firstName, String lastName) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, ?, ?) returning id",
                Long.class, userId, firstName, lastName);
    }

    long service(long practitionerId, int durationMinutes) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', ?, 3000) returning id",
                Long.class, practitionerId, durationMinutes);
    }

    // an evaluation visit of 30 minutes (D-043)
    long evaluation(long practitionerId) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents, evaluation) values (?, 'Valutazione', 30, 3000, true) returning id",
                Long.class, practitionerId);
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
        long id = jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, hold_expires_at, approval_deadline,
                                     free_cancellation_until)
                values (?, ?, ?, ?, ?, ?, 'CUSTOMER', 'CARD', 3000, ?, ?, ?)
                returning id
                """, Long.class,
                customerId, practitionerId, serviceId, at(startsAt), at(endsAt), status,
                at(holdExpiresAt), status.equals("PENDING_APPROVAL") ? at(LATER) : null, noticeBefore(startsAt));
        if (status.equals("PENDING_APPROVAL")) {
            heldPayment(id);
        }
        return id;
    }

    // A request waiting for the practitioner has its amount held on the card (D-031): the payment AUTHORIZED,
    // on the test account "alpha", that the capture of an approval needs (guide 33)
    void heldPayment(long bookingId) {
        jdbc.update("""
                insert into payment (purpose, booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url,
                                     stripe_payment_intent_id)
                values ('BOOKING', ?, 'alpha', 3000, 'AUTHORIZED', 'cs_test_seed_' || ?, 'https://checkout.stripe.test/c/pay/seed',
                        'pi_test_seed_' || ?)
                """, bookingId, bookingId, bookingId);
    }

    // A lot of credits of this customer for this service (step 10): a gift of the practitioner, the simplest
    // valid lot, with its LOT_CREATED movement. validUntil: the last day it can be used, a day of Rome (D-069)
    long creditLot(long practitionerId, long serviceId, long customerId, String validUntil, int credits) {
        long lot = jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', (select user_id from practitioner where id = ?), 'Prova', ?::date, now())
                returning id
                """, Long.class, customerId, practitionerId, serviceId, practitionerId, validUntil);
        jdbc.update("""
                insert into credit_movement (credit_lot_id, amount, reason, created_at)
                values (?, ?, 'LOT_CREATED', now())
                """, lot, credits);
        return lot;
    }

    long balance(long lotId) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from credit_movement where credit_lot_id = ?",
                Long.class, lotId);
    }

    // the movements of a booking, as "REASON amount lot"
    java.util.List<String> movementsOf(long bookingId) {
        return jdbc.queryForList("""
                select reason || ' ' || amount || ' ' || credit_lot_id from credit_movement
                where booking_id = ? order by id
                """, String.class, bookingId);
    }

    // a booking made by the practitioner from the panel, to be paid in the studio (D-024, D-040)
    long panelBooking(long practitionerId, long serviceId, long customerId, String startsAt, String endsAt) {
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, free_cancellation_until)
                values (?, ?, ?, ?, ?, 'CONFIRMED', 'PRACTITIONER', 'IN_STUDIO', 3000, ?)
                returning id
                """, Long.class, customerId, practitionerId, serviceId, at(startsAt), at(endsAt),
                noticeBefore(startsAt));
    }

    // A confirmed session of one hour (step 11): paid by CARD or with a CREDIT by the customer's request, or
    // IN_STUDIO from the panel. With a credit, give the lot: its -1 is written too
    long confirmed(long practitionerId, long serviceId, long customerId, String startsAt, String paidWith,
                   Long creditLot) {
        long id = jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, free_cancellation_until, decided_at)
                values (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 3000, ?, now())
                returning id
                """, Long.class, customerId, practitionerId, serviceId, at(startsAt), at(startsAt).plusHours(1),
                paidWith.equals("IN_STUDIO") ? "PRACTITIONER" : "CUSTOMER", paidWith, noticeBefore(startsAt));
        if (creditLot != null) {
            jdbc.update("""
                    insert into credit_movement (credit_lot_id, amount, reason, booking_id, created_at)
                    values (?, -1, 'BOOKING', ?, now())
                    """, creditLot, id);
        }
        return id;
    }

    // the default notice of 24 hours, photographed as the application does (V16, guide 42, part 6)
    static OffsetDateTime noticeBefore(String startsAt) {
        return at(startsAt).minusHours(24);
    }

    void absence(long practitionerId, String startsAt, String endsAt) {
        jdbc.update("insert into unavailability (practitioner_id, starts_at, ends_at) values (?, ?, ?)",
                practitionerId, at(startsAt), at(endsAt));
    }

    static OffsetDateTime at(String time) {
        return time == null ? null : OffsetDateTime.parse(time);
    }
}
