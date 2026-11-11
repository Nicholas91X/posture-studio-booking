package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// Booking with a credit, the credit side (guide 38, parts 2, 5 and 7): which lot is used, -1, +1. With the
// database and plain SQL rows; every test in a transaction rolled back at the end, as the locks need
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, CreditService.class})
class CreditServiceTest {

    // Wednesday 10 February 2027, 10:00 in Rome
    private static final Instant SESSION = Instant.parse("2027-02-10T09:00:00Z");

    @Autowired
    CreditService credits;

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna);
        cliente = data.customer("cliente@example.com");
    }

    // ***** which lot (FEFO, guide 38, part 5) *****

    @Test
    void theLotThatEndsFirstIsUsedFirst() {
        lot(cliente, anna, seduta, "2027-03-31", 5);
        long sooner = lot(cliente, anna, seduta, "2027-02-28", 2);

        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).contains(sooner);
    }

    @Test
    void onTheSameDayTheOlderLotIsUsedFirst() {
        long older = lot(cliente, anna, seduta, "2027-02-28", 1);
        lot(cliente, anna, seduta, "2027-02-28", 1);

        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).contains(older);
    }

    // guide 38, part 2: the session must fall on or before the last valid day, in Rome
    @Test
    void aLotThatEndsBeforeTheDayOfTheSessionIsNotUsed() {
        lot(cliente, anna, seduta, "2027-02-09", 5);

        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).isEmpty();
        assertThat(credits.hasUsableCredit(cliente, anna, seduta, SESSION)).isFalse();
    }

    @Test
    void theLastValidDayCoversTheWholeDayOfRome() {
        long lot = lot(cliente, anna, seduta, "2027-02-10", 1);

        // 23:30 in Rome on 10 February is still that day; 00:30 on the 11th is not
        assertThat(credits.lockUsableLot(cliente, anna, seduta, Instant.parse("2027-02-10T22:30:00Z"))).contains(lot);
        assertThat(credits.lockUsableLot(cliente, anna, seduta, Instant.parse("2027-02-10T23:30:00Z"))).isEmpty();
    }

    @Test
    void anEmptyLotIsSkippedForTheNextOne() {
        long empty = lot(cliente, anna, seduta, "2027-02-15", 1);
        data.adjustment(empty, -1, data.userOf(anna));
        long next = lot(cliente, anna, seduta, "2027-03-31", 1);

        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).contains(next);
    }

    @Test
    void onlyTheCreditsOfThisCustomerPractitionerAndServiceCount() {
        long marco = data.practitioner("marco@example.com");
        long altroServizio = data.service(anna);
        long altroCliente = data.customer("altro@example.com");
        lot(altroCliente, anna, seduta, "2027-03-31", 5);
        lot(cliente, anna, altroServizio, "2027-03-31", 5);
        lot(cliente, marco, data.service(marco), "2027-03-31", 5);

        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).isEmpty();
        assertThat(credits.hasUsableCredit(cliente, anna, seduta, SESSION)).isFalse();
    }

    // ***** -1 and +1 (guide 38, part 7) *****

    @Test
    void aCommittedCreditGoesBackToTheSameLotOnce() {
        long lot = lot(cliente, anna, seduta, "2027-02-28", 2);
        long booking = data.creditBooking(anna, seduta, cliente);

        credits.commit(lot, booking);
        assertThat(data.balance(lot)).isEqualTo(1);

        assertThat(credits.release(booking)).isTrue();
        assertThat(credits.release(booking)).isFalse();
        assertThat(data.balance(lot)).isEqualTo(2);
        assertThat(jdbc.queryForList("select reason || ' ' || amount || ' ' || credit_lot_id from credit_movement"
                + " where booking_id = ? order by id", String.class, booking))
                .containsExactly("BOOKING -1 " + lot, "BOOKING_RELEASE 1 " + lot);
    }

    // a booking paid by card (or in the studio) took no credit: nothing to give back
    @Test
    void aBookingWithoutACreditGivesNothingBack() {
        long lot = lot(cliente, anna, seduta, "2027-02-28", 2);
        long booking = data.creditBooking(anna, seduta, cliente);

        assertThat(credits.release(booking)).isFalse();
        assertThat(data.balance(lot)).isEqualTo(2);
    }

    // a lot whose last valid day is the day of the session: still usable by its session (guide 38, part 2)
    @Test
    void aCreditThatComesBackFindsItsLotWithItsLastValidDay() {
        long lot = lot(cliente, anna, seduta, "2027-02-10", 1);
        long booking = data.creditBooking(anna, seduta, cliente);
        credits.commit(lot, booking);

        credits.release(booking);

        assertThat(jdbc.queryForObject("select valid_until::text from credit_lot where id = ?", String.class, lot))
                .isEqualTo("2027-02-10");
        assertThat(credits.lockUsableLot(cliente, anna, seduta, SESSION)).contains(lot);
    }

    private long lot(long customer, long practitioner, long service, String validUntil, int credits) {
        long lot = jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, 'Prova', ?::date, now())
                returning id
                """, Long.class, customer, practitioner, service, data.userOf(practitioner), validUntil);
        data.lotCreated(lot, credits);
        return lot;
    }
}
