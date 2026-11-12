package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// The credit that comes back from a cancellation, the credit side (D-076, guide 43, part 4.2): the same lot, or a new
// lot with the minimum validity, once only for the customer. Today is Friday 6 November 2026 in Rome: 90 days later
// is Thursday 4 February 2027, the minimum day. Each test in a transaction rolled back at the end
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, CreditService.class})
class CreditCancellationServiceTest {

    private static final int DAYS = 90;
    private static final LocalDate MINIMUM = LocalDate.of(2027, 2, 4);

    @Autowired
    CreditService credits;

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long seduta;
    private long cliente;
    private long booking;

    @BeforeEach
    void aSessionPaidWithACredit() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna);
        cliente = data.customer("cliente@example.com");
        booking = data.creditBooking(anna, seduta, cliente);
    }

    // ***** the same lot, or a new one (the boundary of the minimum day) *****

    @Test
    void aLotThatLastsUntilTheMinimumDayGetsTheCreditBack() {
        long lot = lotPaying(booking, "GRANT", MINIMUM);

        ReturnedCredit back = credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER);

        assertThat(back).isEqualTo(new ReturnedCredit(lot, MINIMUM));
        assertThat(movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "CANCELLATION_RESTORE 1 " + lot);
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    @Test
    void aLotThatLastsLongerGetsTheCreditBack() {
        long lot = lotPaying(booking, "GRANT", MINIMUM.plusDays(1));

        assertThat(credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER).lotId()).isEqualTo(lot);
    }

    // one day short of the minimum: the credit goes to a new lot of 1, until the minimum day; the old lot keeps its -1
    @Test
    void aLotThatEndsTooSoonGivesANewLotWithTheMinimumValidity() {
        long lot = lotPaying(booking, "GRANT", MINIMUM.minusDays(1));

        ReturnedCredit back = credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER);

        assertThat(back.lotId()).isNotEqualTo(lot);
        assertThat(back.validUntil()).isEqualTo(MINIMUM);
        assertThat(sourceOf(back.lotId())).isEqualTo("CANCELLATION");
        assertThat(movementsOf(booking)).containsExactly("BOOKING -1 " + lot, "LOT_CREATED 1 " + back.lotId());
        assertThat(data.balance(lot)).isEqualTo(4);
        assertThat(data.balance(back.lotId())).isEqualTo(1);
    }

    // ***** once only (D-076, guide 42, part 8) *****

    // a credit already extended by a cancellation goes back to its lot, even if it ends soon
    @Test
    void aCreditAlreadyExtendedIsNotExtendedAgainByTheCustomer() {
        long lot = lotPaying(booking, "CANCELLATION", LocalDate.of(2026, 11, 20));

        ReturnedCredit back = credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER);

        assertThat(back).isEqualTo(new ReturnedCredit(lot, LocalDate.of(2026, 11, 20)));
        assertThat(lotsOf(cliente)).isEqualTo(1);
    }

    // the studio cancelled, not the customer: no loop is possible, the minimum validity holds (D-076)
    @Test
    void whenTheStudioCancelsEvenAnExtendedCreditGetsTheMinimumValidity() {
        long lot = lotPaying(booking, "CANCELLATION", LocalDate.of(2026, 11, 20));

        ReturnedCredit back = credits.restoreOnCancellation(booking, DAYS, CancelledBy.STUDIO);

        assertThat(back.lotId()).isNotEqualTo(lot);
        assertThat(back.validUntil()).isEqualTo(MINIMUM);
    }

    // ***** a session paid by card *****

    @Test
    void aCardSessionGivesALotOfOneThatKnowsItsBooking() {
        ReturnedCredit back = credits.lotFromCancellation(cliente, anna, seduta, booking, DAYS);

        assertThat(back.validUntil()).isEqualTo(MINIMUM);
        assertThat(sourceOf(back.lotId())).isEqualTo("CANCELLATION");
        assertThat(movementsOf(booking)).containsExactly("LOT_CREATED 1 " + back.lotId());
    }

    // ***** what came back, and never twice *****

    @Test
    void theReturnsOfAListOfBookingsInOneQuestion() {
        long other = data.creditBooking(anna, seduta, cliente);
        long nothing = data.creditBooking(anna, seduta, cliente);
        long lot = lotPaying(booking, "GRANT", MINIMUM.plusDays(10));
        ReturnedCredit card = credits.lotFromCancellation(cliente, anna, seduta, other, DAYS);
        credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER);

        assertThat(credits.returnedTo(List.of(booking, other, nothing))).containsOnly(
                java.util.Map.entry(booking, new ReturnedCredit(lot, MINIMUM.plusDays(10))),
                java.util.Map.entry(other, card));
        assertThat(credits.returnedTo(List.of())).isEmpty();
    }

    // the lock on the booking stops a second cancellation; if a bug let it through, the database stops it (V16)
    @Test
    void aSecondReturnForTheSameBookingIsRefusedByTheDatabase() {
        lotPaying(booking, "GRANT", MINIMUM);
        credits.restoreOnCancellation(booking, DAYS, CancelledBy.CUSTOMER);

        assertThatThrownBy(() -> credits.lotFromCancellation(cliente, anna, seduta, booking, DAYS))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("credit_movement_one_return_per_booking_uq");
    }

    // ***** helpers *****

    // a lot of 5 credits of this source, one of them taken by the booking
    private long lotPaying(long bookingId, String source, LocalDate validUntil) {
        long lot = source.equals("GRANT")
                ? jdbc.queryForObject("""
                        insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                                valid_until, created_at)
                        values (?, ?, ?, 'GRANT', ?, 'Prova', ?, now()) returning id
                        """, Long.class, cliente, anna, seduta, data.userOf(anna), validUntil)
                : jdbc.queryForObject("""
                        insert into credit_lot (customer_id, practitioner_id, service_id, source, valid_until, created_at)
                        values (?, ?, ?, 'CANCELLATION', ?, now()) returning id
                        """, Long.class, cliente, anna, seduta, validUntil);
        data.lotCreated(lot, 5);
        data.movement(lot, -1, "BOOKING", bookingId, null, null);
        return lot;
    }

    private List<String> movementsOf(long bookingId) {
        return jdbc.queryForList("""
                select reason || ' ' || amount || ' ' || credit_lot_id from credit_movement
                where booking_id = ? order by id
                """, String.class, bookingId);
    }

    private String sourceOf(long lotId) {
        return jdbc.queryForObject("select source from credit_lot where id = ?", String.class, lotId);
    }

    private long lotsOf(long customerId) {
        return jdbc.queryForObject("select count(*) from credit_lot where customer_id = ?", Long.class, customerId);
    }
}
