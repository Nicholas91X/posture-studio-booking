package com.posturestudio.booking.booking;

import static com.posturestudio.booking.booking.BookingTestData.at;
import static org.assertj.core.api.Assertions.assertThat;

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

// Tests the constraints of V4 (and of V15: paid with a credit) on booking with plain SQL: it is the database that defends itself.
// The two inserts of an overlap run one after the other in the same transaction; two simultaneous
// transactions are in BookingConcurrencyTest. Monday 9 November 2026, times in Rome
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class BookingSchemaTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_930 = "2026-11-09T09:30+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";
    private static final String MONDAY_1030 = "2026-11-09T10:30+01:00";
    private static final String MONDAY_11 = "2026-11-09T11:00+01:00";

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long marco;
    private long annasService;
    private long customer;

    @BeforeEach
    void insertPeople() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        annasService = data.service(anna, 60);
        customer = data.customer("cliente@example.com");
    }

    // ***** no overlap (D-013, D-042) *****

    @Test
    void overlappingBookingsOfTheSamePractitionerAreRejected() {
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        assertViolates("booking_no_overlap_ex",
                () -> data.booking(anna, annasService, customer, MONDAY_930, MONDAY_1030, "CONFIRMED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "PENDING_APPROVAL", "CONFIRMED"})
    void everyStateThatHoldsTheTimeBlocksTheSameSlot(String status) {
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, status);

        assertViolates("booking_no_overlap_ex",
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT"));
    }

    @Test
    void backToBackBookingsAreAccepted() {
        // half-open ranges: 9-10 and 10-11 touch but do not overlap
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        Assertions.assertDoesNotThrow(
                () -> data.booking(anna, annasService, customer, MONDAY_10, MONDAY_11, "CONFIRMED"));
    }

    @Test
    void twoPractitionersCanBeBookedAtTheSameTime() {
        // the constraint is per practitioner: one room, but overlaps are flagged, not prevented (D-043)
        long marcosService = data.service(marco, 60);
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        Assertions.assertDoesNotThrow(
                () -> data.booking(marco, marcosService, customer, MONDAY_9, MONDAY_10, "CONFIRMED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "REJECTED", "APPROVAL_EXPIRED", "WITHDRAWN"})
    void finalStatesDoNotHoldTheTime(String status) {
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, status);

        Assertions.assertDoesNotThrow(
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT"));
    }

    @Test
    void pendingPaymentWithAnExpiredHoldStillHoldsTheTime() {
        // the trap of guide 18, 3.5: an index predicate cannot use now(), so for the constraint
        // this row holds the time until someone changes its status (piece 2 and the job of piece 4)
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", "2026-11-06T09:45+01:00");

        assertViolates("booking_no_overlap_ex",
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT"));
    }

    @Test
    void anExpiredBookingLeavesTheIndexAndFreesTheTime() {
        long expiring = data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT");

        // what the job of piece 4 will do
        jdbc.update("update booking set status = 'EXPIRED' where id = ?", expiring);

        Assertions.assertDoesNotThrow(
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT"));
    }

    @Test
    void movingAFinalBookingBackToAHoldingStateIsCheckedToo() {
        // the constraint runs on update as well: a booking cannot come back over another one
        long rejected = data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "REJECTED");
        data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        assertViolates("booking_no_overlap_ex",
                () -> jdbc.update("update booking set status = 'CONFIRMED' where id = ?", rejected));
    }

    // ***** foreign keys *****

    @Test
    void serviceOfAnotherPractitionerIsRejected() {
        long marcosService = data.service(marco, 60);

        assertViolates("booking_service_practitioner_fk",
                () -> data.booking(anna, marcosService, customer, MONDAY_9, MONDAY_10, "CONFIRMED"));
    }

    @Test
    void missingCustomerIsRejected() {
        assertViolates("booking_customer_fk",
                () -> data.booking(anna, annasService, 999999, MONDAY_9, MONDAY_10, "CONFIRMED"));
    }

    // ***** one row (one violation per test: after a failed statement PostgreSQL aborts the test
    // transaction, and any further statement would fail for that reason instead) *****

    @Test
    void endNotAfterStartIsRejected() {
        assertViolates("booking_times_ck",
                () -> data.booking(anna, annasService, customer, MONDAY_10, MONDAY_10, "CONFIRMED"));
    }

    @ParameterizedTest
    // CANCELLED_LATE exists in the data model, but arrives with step 11 (D-042)
    @ValueSource(strings = {"CANCELLED_LATE", "PAID", ""})
    void unknownStatusIsRejected(String status) {
        assertViolates("booking_status_ck",
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, status));
    }

    @Test
    void pendingPaymentWithoutHoldIsRejected() {
        assertViolates("booking_hold_ck",
                () -> data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", null));
    }

    @Test
    void pendingApprovalWithoutDeadlineIsRejected() {
        assertViolates("booking_deadline_ck", () -> insertWith("PENDING_APPROVAL", "CUSTOMER", "CARD", 3000, null));
    }

    // ***** paid with a credit (V15, guide 36, part 11) *****

    @Test
    void aCustomerCanPayWithACredit() {
        Assertions.assertDoesNotThrow(() -> insertWith("CONFIRMED", "CUSTOMER", "CREDIT", 3000, null));
    }

    // a booking from the panel uses the credit of the customer when there is one (D-024)
    @Test
    void aPractitionerCanBookWithTheCreditOfTheCustomer() {
        Assertions.assertDoesNotThrow(() -> insertWith("CONFIRMED", "PRACTITIONER", "CREDIT", 3000, null));
    }

    @Test
    void aRequestWithACreditWaitsForTheApprovalOnly() {
        Assertions.assertDoesNotThrow(
                () -> insertWith("PENDING_APPROVAL", "CUSTOMER", "CREDIT", 3000, BookingTestData.LATER));
    }

    // with its hold, so that only this rule is broken
    @Test
    void aBookingWithACreditNeverWaitsForAPayment() {
        assertViolates("booking_credit_no_hold_ck", () -> jdbc.update("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, hold_expires_at)
                values (?, ?, ?, ?, ?, 'PENDING_PAYMENT', 'CUSTOMER', 'CREDIT', 3000, ?)
                """, customer, anna, annasService, at(MONDAY_9), at(MONDAY_10), at(BookingTestData.LATER)));
    }

    @Test
    void anUnknownWayOfPayingIsRejected() {
        assertViolates("booking_paid_with_ck", () -> insertWith("CONFIRMED", "CUSTOMER", "CASH", 3000, null));
    }

    @Test
    void unknownAuthorIsRejected() {
        assertViolates("booking_booked_by_ck", () -> insertWith("CONFIRMED", "ADMIN", "CARD", 3000, null));
    }

    @Test
    void customerCannotChooseToPayInTheStudio() {
        // paying in the studio is a choice of the practitioner from the panel (D-024, D-040)
        assertViolates("booking_in_studio_ck", () -> insertWith("CONFIRMED", "CUSTOMER", "IN_STUDIO", 3000, null));
    }

    @Test
    void practitionerCanBookToBePaidInTheStudio() {
        Assertions.assertDoesNotThrow(() -> insertWith("CONFIRMED", "PRACTITIONER", "IN_STUDIO", 3000, null));
    }

    @Test
    void zeroPriceIsRejected() {
        assertViolates("booking_price_cents_ck", () -> insertWith("CONFIRMED", "CUSTOMER", "CARD", 0, null));
    }

    @Test
    void columnsOfAPreviousStateStayWhenTheStatusChanges() {
        // the hold is history: a confirmed booking keeps it, and no check asks to clear it
        long booking = data.booking(anna, annasService, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT");

        int updated = jdbc.update(
                "update booking set status = 'PENDING_APPROVAL', approval_deadline = ? where id = ?",
                at(BookingTestData.LATER), booking);

        assertThat(updated).isEqualTo(1);
    }

    // ***** helpers *****

    // Monday 9-10 for Anna, with the columns under test given explicitly
    private long insertWith(String status, String bookedBy, String paidWith, long priceCents, String deadline) {
        return jdbc.queryForObject("""
                insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status,
                                     booked_by, paid_with, price_cents, approval_deadline)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                customer, anna, annasService, at(MONDAY_9), at(MONDAY_10), status, bookedBy, paidWith, priceCents,
                at(deadline));
    }

    private void assertViolates(String constraintName, Runnable statement) {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class, statement::run);
        assertThat(exception.getMessage()).contains(constraintName);
    }
}
