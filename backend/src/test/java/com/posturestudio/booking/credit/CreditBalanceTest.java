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

// No lot goes below zero (guide 36, part 7): the trigger credit_movement_keep_balance, with plain SQL, one
// transaction at a time. Two simultaneous transactions are in CreditBalanceConcurrencyTest
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CreditBalanceTest {

    private static final String BELOW_ZERO = "balance cannot go below zero";

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long annasUser;
    private long seduta;
    private long customer;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        seduta = data.service(anna);
        customer = data.customer("cliente@example.com");
    }

    @Test
    void theLastCreditCanBeUsed() {
        long lot = data.lotWith(customer, anna, seduta, 1);

        data.adjustment(lot, -1, annasUser);

        assertThat(data.balance(lot)).isZero();
    }

    @Test
    void anEmptyLotGivesNothing() {
        long lot = data.lotWith(customer, anna, seduta, 1);
        data.adjustment(lot, -1, annasUser);
        long booking = data.creditBooking(anna, seduta, customer);

        assertRefused(() -> data.movement(lot, -1, "BOOKING", booking, null, null));
    }

    @Test
    void aCorrectionCannotTakeMoreThanThereIs() {
        long lot = data.lotWith(customer, anna, seduta, 1);

        assertRefused(() -> data.adjustment(lot, -2, annasUser));
    }

    @Test
    void aLotWithoutMovementsHasNothingToGive() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused(() -> data.adjustment(lot, -1, annasUser));
    }

    // adding can never go below zero: no lock, no check
    @Test
    void creditsCanAlwaysBeAdded() {
        long lot = data.lotWith(customer, anna, seduta, 1);
        data.adjustment(lot, -1, annasUser);

        data.adjustment(lot, 2, annasUser);

        assertThat(data.balance(lot)).isEqualTo(2);
    }

    // the balance is per lot: another lot of the same customer does not help
    @Test
    void theBalanceIsOfTheLot() {
        long empty = data.grantLot(customer, anna, seduta);
        data.lotWith(customer, anna, seduta, 5);

        assertRefused(() -> data.adjustment(empty, -1, annasUser));
    }

    private static void assertRefused(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(BELOW_ZERO);
    }
}
