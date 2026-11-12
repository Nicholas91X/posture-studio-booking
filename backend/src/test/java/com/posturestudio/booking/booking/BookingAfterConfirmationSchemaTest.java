package com.posturestudio.booking.booking;

import static com.posturestudio.booking.booking.BookingTestData.at;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The constraints of V16 on booking (guide 42, parts 5, 6, 9), with plain SQL: cancellations say who and when,
// a no-show says who marked it, the notice is photographed and always before the start, and no state of step 11
// holds the time. One violation per test (after a failed statement PostgreSQL aborts the test transaction), and
// only one rule broken by each row: PostgreSQL checks the constraints in alphabetical order
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class BookingAfterConfirmationSchemaTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";
    // the cancellation, three days before
    private static final String FRIDAY_BEFORE = "2026-11-06T12:00+01:00";

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long annasUser;
    private long service;
    private long customer;
    private long customersUser;

    @BeforeEach
    void rows() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, anna);
        service = data.service(anna, 60);
        customer = data.customer("cliente@example.com");
        customersUser = jdbc.queryForObject("select user_id from customer where id = ?", Long.class, customer);
    }

    // ***** who and when a booking was cancelled (guide 42, part 5) *****

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED_IN_TIME", "CANCELLED_LATE", "CANCELLED_BY_STUDIO"})
    void aCancellationWithWhoAndWhenIsAccepted(String status) {
        long id = insert(new Row(status).cancelled(FRIDAY_BEFORE, customersUser));

        assertThat(jdbc.queryForObject("select status from booking where id = ?", String.class, id)).isEqualTo(status);
    }

    @Test
    void aCancellationWithoutWhoIsRejected() {
        assertViolates("booking_cancellation_trace_ck",
                () -> insert(new Row("CANCELLED_IN_TIME").cancelled(FRIDAY_BEFORE, null)));
    }

    @Test
    void aCancellationWithoutWhenIsRejected() {
        assertViolates("booking_cancellation_trace_ck",
                () -> insert(new Row("CANCELLED_BY_STUDIO").cancelled(null, annasUser)));
    }

    // "who cancelled" belongs only to the cancellations
    @Test
    void aConfirmedBookingHasNoCanceller() {
        assertViolates("booking_cancellation_trace_ck",
                () -> insert(new Row("CONFIRMED").cancelled(FRIDAY_BEFORE, customersUser)));
    }

    // the withdrawals of step 7 have only their moment: they stay valid
    @Test
    void aWithdrawalWithOnlyItsMomentIsStillAccepted() {
        insert(new Row("WITHDRAWN").cancelled(FRIDAY_BEFORE, null));
    }

    @Test
    void theCancellerIsARealAccount() {
        assertViolates("booking_cancelled_by_fk",
                () -> insert(new Row("CANCELLED_LATE").cancelled(FRIDAY_BEFORE, 999999L)));
    }

    // ***** completed and no-show (guide 42, part 9) *****

    // the job completes a session without a mark; a correction back from a no-show keeps who made it
    @Test
    void aCompletedSessionWithOrWithoutAMarkIsAccepted() {
        insert(new Row("COMPLETED"));
        insert(new Row("COMPLETED").marked(MONDAY_10, annasUser).at("2026-11-09T11:00+01:00", "2026-11-09T12:00+01:00"));
    }

    @Test
    void aNoShowWithItsMarkIsAccepted() {
        insert(new Row("NO_SHOW").marked(MONDAY_10, annasUser));
    }

    @Test
    void aNoShowWithoutAMarkIsRejected() {
        assertViolates("booking_attendance_trace_ck", () -> insert(new Row("NO_SHOW")));
    }

    @Test
    void aMarkWithoutItsMomentIsRejected() {
        assertViolates("booking_attendance_trace_ck", () -> insert(new Row("NO_SHOW").marked(null, annasUser)));
    }

    // a session still to come cannot be marked
    @Test
    void aConfirmedSessionCarriesNoMark() {
        assertViolates("booking_attendance_trace_ck", () -> insert(new Row("CONFIRMED").marked(MONDAY_10, annasUser)));
    }

    // ***** the notice photographed (guide 42, part 6) *****

    @Test
    void aBookingWithoutItsNoticeIsRejected() {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> insert(new Row("CONFIRMED").freeUntil(null)));
        assertThat(exception.getMessage()).contains("free_cancellation_until");
    }

    @ParameterizedTest
    @ValueSource(strings = {MONDAY_9, MONDAY_10})
    void theFreeCancellationEndsBeforeTheStart(String freeUntil) {
        assertViolates("booking_free_cancellation_ck", () -> insert(new Row("CONFIRMED").freeUntil(freeUntil)));
    }

    // ***** no state of step 11 holds the time (guide 42, part 2) *****

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED_IN_TIME", "CANCELLED_LATE", "CANCELLED_BY_STUDIO", "COMPLETED", "NO_SHOW"})
    void aSessionAfterItsConfirmationFreesTheTime(String status) {
        Row row = new Row(status);
        if (status.startsWith("CANCELLED")) {
            row.cancelled(FRIDAY_BEFORE, customersUser);
        } else if (status.equals("NO_SHOW")) {
            row.marked(MONDAY_10, annasUser);
        }
        insert(row);

        // the same hour, booked again
        insert(new Row("CONFIRMED"));
    }

    // ***** helpers *****

    // Monday 9-10 for Anna, free cancellation until 24 hours before, unless a test says otherwise
    private final class Row {
        final String status;
        String startsAt = MONDAY_9;
        String endsAt = MONDAY_10;
        String freeUntil = "2026-11-08T09:00+01:00";
        String cancelledAt;
        Long cancelledBy;
        String markedAt;
        Long markedBy;

        Row(String status) {
            this.status = status;
        }

        Row cancelled(String when, Long who) {
            this.cancelledAt = when;
            this.cancelledBy = who;
            return this;
        }

        Row marked(String when, Long who) {
            this.markedAt = when;
            this.markedBy = who;
            return this;
        }

        Row freeUntil(String instant) {
            this.freeUntil = instant;
            return this;
        }

        Row at(String start, String end) {
            this.startsAt = start;
            this.endsAt = end;
            this.freeUntil = OffsetDateTime.parse(start).minusHours(24).toString();
            return this;
        }
    }

    private long insert(Row row) {
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, free_cancellation_until,
                                     cancelled_at, cancelled_by, attendance_marked_at, attendance_marked_by)
                values (?, ?, ?, ?, ?, ?, 'CUSTOMER', 'CREDIT', 3000, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                customer, anna, service, at(row.startsAt), at(row.endsAt), row.status, at(row.freeUntil),
                at(row.cancelledAt), row.cancelledBy, at(row.markedAt), row.markedBy);
    }

    private void assertViolates(String constraintName, Runnable statement) {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class, statement::run);
        assertThat(exception.getMessage()).contains(constraintName);
    }
}
