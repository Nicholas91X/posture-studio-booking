package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The constraints of V16 on the credits (guide 42, parts 7 and 8), with plain SQL: the lot born from a
// cancellation, the +1 that goes back to the same lot, and "a cancellation gives back one credit at most",
// whichever of the two ways the credit comes back
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CreditCancellationSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long seduta;
    private long customer;
    private long booking;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna);
        customer = data.customer("cliente@example.com");
        booking = data.creditBooking(anna, seduta, customer);
    }

    // ***** the shapes *****

    // a session paid by card, cancelled in time: a lot of 1 that knows its booking
    @Test
    void aLotBornFromACancellationIsAccepted() {
        long lot = data.cancellationLot(customer, anna, seduta, booking);

        assertThat(jdbc.queryForObject("select source from credit_lot where id = ?", String.class, lot))
                .isEqualTo("CANCELLATION");
        assertThat(data.balance(lot)).isEqualTo(1);
    }

    // a session paid with a credit, cancelled in time: +1 on the same lot
    @Test
    void aCreditGoesBackToItsLot() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        data.movement(lot, -1, "BOOKING", booking, null, null);

        data.movement(lot, 1, "CANCELLATION_RESTORE", booking, null, null);

        assertThat(data.balance(lot)).isEqualTo(5);
    }

    @Test
    void aRestoreGivesBackExactlyOneCredit() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        data.movement(lot, -1, "BOOKING", booking, null, null);

        assertRefused("credit_movement_shape_ck",
                () -> data.movement(lot, 2, "CANCELLATION_RESTORE", booking, null, null));
    }

    @Test
    void aRestoreNeedsItsBooking() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, 1, "CANCELLATION_RESTORE", null, null, null));
    }

    // a lot born from a booking is a lot of 1: a cancellation gives back one session
    @Test
    void aLotBornFromABookingHoldsOneCredit() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, 2, "LOT_CREATED", booking, null, null));
    }

    // ***** one credit per cancellation (guide 42, 7.1) *****

    // a double click that a bug let through twice, the same way
    @Test
    void aCancellationCreatesOneLotAtMost() {
        data.cancellationLot(customer, anna, seduta, booking);

        assertRefused("credit_movement_one_return_per_booking_uq",
                () -> data.cancellationLot(customer, anna, seduta, booking));
    }

    @Test
    void aCancellationRestoresOneCreditAtMost() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        data.movement(lot, -1, "BOOKING", booking, null, null);
        data.movement(lot, 1, "CANCELLATION_RESTORE", booking, null, null);

        assertRefused("credit_movement_one_return_per_booking_uq",
                () -> data.movement(lot, 1, "CANCELLATION_RESTORE", booking, null, null));
    }

    // the case two separate rules would miss: +1 on the old lot AND a new lot for the same cancellation
    @Test
    void aCancellationNeverGivesBothARestoreAndANewLot() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        data.movement(lot, -1, "BOOKING", booking, null, null);
        data.movement(lot, 1, "CANCELLATION_RESTORE", booking, null, null);

        assertRefused("credit_movement_one_return_per_booking_uq",
                () -> data.cancellationLot(customer, anna, seduta, booking));
    }

    // a rejection that gave the credit back, then a cancellation: impossible in the state machine, refused anyway
    @Test
    void aBookingThatGaveItsCreditBackGetsNothingMore() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        data.movement(lot, -1, "BOOKING", booking, null, null);
        data.movement(lot, 1, "BOOKING_RELEASE", booking, null, null);

        assertRefused("credit_movement_one_return_per_booking_uq",
                () -> data.cancellationLot(customer, anna, seduta, booking));
    }

    @Test
    void differentCancellationsGiveBackIndependently() {
        long other = data.creditBooking(anna, seduta, customer);

        data.cancellationLot(customer, anna, seduta, booking);
        data.cancellationLot(customer, anna, seduta, other);

        assertThat(jdbc.queryForObject("select count(*) from credit_lot where source = 'CANCELLATION'", Long.class))
                .isEqualTo(2);
    }

    private void assertRefused(String constraintName, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }
}
