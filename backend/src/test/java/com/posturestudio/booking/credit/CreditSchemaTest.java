package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

// The constraints of V15 on lots, movements and extensions (guide 36, parts 4, 5, 9), with plain SQL:
// it is the database that defends itself. The triggers have their own tests (CreditLedgerImmutabilityTest,
// CreditBalanceTest)
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CreditSchemaTest {

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

    // ***** credit_lot *****

    @Test
    void aPackageLotAndAGiftLotAreAccepted() {
        long bought = data.packageLot(customer, anna, seduta);
        long gift = data.grantLot(customer, anna, seduta);

        assertThat(jdbc.queryForList("select source from credit_lot where id in (?, ?) order by id", String.class,
                bought, gift)).containsExactly("PACKAGE", "GRANT");
    }

    // D-025 on lots: the service belongs to the practitioner of the lot
    @Test
    void aLotOnTheServiceOfAnotherPractitionerIsRejected() {
        long marco = data.practitioner("marco@example.com");

        assertRefused("credit_lot_service_practitioner_fk", () -> data.grantLot(customer, marco, seduta));
    }

    // LATE_PAYMENT does not exist (D-068); CANCELLATION arrives with step 11
    @ParameterizedTest
    @ValueSource(strings = {"LATE_PAYMENT", "CANCELLATION"})
    void sourcesOfOtherStepsAreRejected(String source) {
        assertRefused("credit_lot_source_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, valid_until, created_at)
                values (?, ?, ?, ?, '2027-02-08', now())
                """, customer, anna, seduta, source));
    }

    // a lot comes from a package if and only if it has its payment (Nicholas, 2026-11-10)
    // without package and price too: only this rule is broken (PostgreSQL checks the constraints in
    // alphabetical order, and credit_lot_package_columns_ck would answer first)
    @Test
    void aPackageLotWithoutItsPaymentIsRejected() {
        assertRefused("credit_lot_payment_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, valid_until, created_at)
                values (?, ?, ?, 'PACKAGE', '2027-02-08', now())
                """, customer, anna, seduta));
    }

    @Test
    void aGiftWithAPaymentIsRejected() {
        long offer = data.packageOffer(anna, seduta);
        long payment = data.packagePayment(customer, offer);

        assertRefused("credit_lot_payment_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, payment_id, package_offer_id,
                                        price_cents, granted_by, note, valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, ?, 12000, ?, 'Omaggio', '2027-02-08', now())
                """, customer, anna, seduta, payment, offer, annasUser));
    }

    @Test
    void aPackageLotWithoutItsPriceIsRejected() {
        long offer = data.packageOffer(anna, seduta);
        long payment = data.packagePayment(customer, offer);

        assertRefused("credit_lot_package_columns_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, payment_id, package_offer_id,
                                        valid_until, created_at)
                values (?, ?, ?, 'PACKAGE', ?, ?, '2027-02-08', now())
                """, customer, anna, seduta, payment, offer));
    }

    // one payment buys one lot: a webhook delivered twice cannot give a package away (guide 36, 4.5)
    @Test
    void aPaymentBuysOneLotAtMost() {
        long offer = data.packageOffer(anna, seduta);
        long payment = data.packagePayment(customer, offer);
        String insert = """
                insert into credit_lot (customer_id, practitioner_id, service_id, source, payment_id, package_offer_id,
                                        price_cents, valid_until, created_at)
                values (?, ?, ?, 'PACKAGE', ?, ?, 12000, '2027-02-08', now())
                """;
        jdbc.update(insert, customer, anna, seduta, payment, offer);

        assertRefused("credit_lot_payment_uq", () -> jdbc.update(insert, customer, anna, seduta, payment, offer));
    }

    @Test
    void aGiftWithoutWhoGaveItIsRejected() {
        assertRefused("credit_lot_grant_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, note, valid_until, created_at)
                values (?, ?, ?, 'GRANT', 'Omaggio', '2027-02-08', now())
                """, customer, anna, seduta));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aGiftWithoutWhyIsRejected(String note) {
        assertRefused("credit_lot_note_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, ?, '2027-02-08', now())
                """, customer, anna, seduta, annasUser, note));
    }

    @Test
    void aGiftWithoutAnyNoteIsRejected() {
        assertRefused("credit_lot_note_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, '2027-02-08', now())
                """, customer, anna, seduta, annasUser));
    }

    // ***** credit_movement: the shape of each reason (guide 36, 5.1) *****

    @Test
    void everyReasonOfStep10IsAcceptedInItsShape() {
        long lot = data.grantLot(customer, anna, seduta);
        long booking = data.creditBooking(anna, seduta, customer);

        data.movement(lot, 3, "LOT_CREATED", null, null, null);
        data.movement(lot, -1, "BOOKING", booking, null, null);
        data.movement(lot, 1, "BOOKING_RELEASE", booking, null, null);
        data.adjustment(lot, -2, annasUser);

        assertThat(data.balance(lot)).isEqualTo(1);
    }

    // on a lot with credits: on an empty one the balance trigger, which runs BEFORE the checks, would answer first
    @Test
    void aLotIsNotBornWithANegativeAmount() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, -3, "LOT_CREATED", null, null, null));
    }

    @Test
    void aBookingTakesExactlyOneCredit() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        long booking = data.creditBooking(anna, seduta, customer);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, -2, "BOOKING", booking, null, null));
    }

    @Test
    void aBookingMovementNeedsItsBooking() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, -1, "BOOKING", null, null, null));
    }

    @Test
    void aReleaseGivesBackExactlyOneCredit() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        long booking = data.creditBooking(anna, seduta, customer);
        data.movement(lot, -1, "BOOKING", booking, null, null);

        assertRefused("credit_movement_shape_ck", () -> data.movement(lot, 2, "BOOKING_RELEASE", booking, null, null));
    }

    @Test
    void anAdjustmentWithoutWhoAndWhyIsRejected() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_trace_ck", () -> data.movement(lot, -1, "ADJUSTMENT", null, null, null));
    }

    @Test
    void anAdjustmentWithABlankNoteIsRejected() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_trace_ck", () -> data.movement(lot, -1, "ADJUSTMENT", null, annasUser, "  "));
    }

    @Test
    void onlyAnAdjustmentCarriesATrace() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused("credit_movement_trace_ck",
                () -> data.movement(lot, 5, "LOT_CREATED", null, annasUser, "Perché sì"));
    }

    @Test
    void anAdjustmentOfZeroIsRejected() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_amount_ck", () -> data.adjustment(lot, 0, annasUser));
    }

    // APPROVAL_RELEASE was the provisional name (now BOOKING_RELEASE); CANCELLATION_RESTORE arrives with step 11
    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL_RELEASE", "CANCELLATION_RESTORE", "EXPIRED"})
    void reasonsOutsideStep10AreRejected(String reason) {
        long lot = data.lotWith(customer, anna, seduta, 5);
        long booking = data.creditBooking(anna, seduta, customer);

        assertRefused("credit_movement_reason_ck", () -> data.movement(lot, 1, reason, booking, null, null));
    }

    // ***** "only once" (guide 36, 5.2) *****

    @Test
    void aLotIsBornOnce() {
        long lot = data.lotWith(customer, anna, seduta, 5);

        assertRefused("credit_movement_one_lot_created_uq", () -> data.lotCreated(lot, 5));
    }

    @Test
    void aBookingTakesOneCreditAtMostEvenFromTwoLots() {
        long first = data.lotWith(customer, anna, seduta, 5);
        long second = data.lotWith(customer, anna, seduta, 5);
        long booking = data.creditBooking(anna, seduta, customer);
        data.movement(first, -1, "BOOKING", booking, null, null);

        assertRefused("credit_movement_one_debit_per_booking_uq",
                () -> data.movement(second, -1, "BOOKING", booking, null, null));
    }

    // a rejection and an expired answer both let through by a bug would not give the credit back twice
    @Test
    void aBookingGivesBackOneCreditAtMost() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        long booking = data.creditBooking(anna, seduta, customer);
        data.movement(lot, -1, "BOOKING", booking, null, null);
        data.movement(lot, 1, "BOOKING_RELEASE", booking, null, null);

        assertRefused("credit_movement_one_return_per_booking_uq",
                () -> data.movement(lot, 1, "BOOKING_RELEASE", booking, null, null));
    }

    @Test
    void differentBookingsTakeAndGiveBackIndependently() {
        long lot = data.lotWith(customer, anna, seduta, 5);
        long first = data.creditBooking(anna, seduta, customer);
        long second = data.creditBooking(anna, seduta, customer);

        data.movement(lot, -1, "BOOKING", first, null, null);
        data.movement(lot, -1, "BOOKING", second, null, null);
        data.movement(lot, 1, "BOOKING_RELEASE", first, null, null);
        data.movement(lot, 1, "BOOKING_RELEASE", second, null, null);

        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // ***** credit_lot_extension *****

    @Test
    void anExtensionMovesForwardOnly() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused("credit_lot_extension_forward_ck", () -> extension(lot, "2027-02-08", "2027-02-01", "Errore"));
    }

    @Test
    void anExtensionToTheSameDayIsRejected() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused("credit_lot_extension_forward_ck", () -> extension(lot, "2027-02-08", "2027-02-08", "Niente"));
    }

    @Test
    void anExtensionSaysWhy() {
        long lot = data.grantLot(customer, anna, seduta);

        assertRefused("credit_lot_extension_note_ck", () -> extension(lot, "2027-02-08", "2027-03-08", " "));
    }

    private int extension(long lot, String oldDay, String newDay, String note) {
        return jdbc.update("""
                insert into credit_lot_extension (credit_lot_id, old_valid_until, new_valid_until, extended_by, note,
                                                  created_at)
                values (?, ?::date, ?::date, ?, ?, now())
                """, lot, oldDay, newDay, annasUser, note);
    }

    private static void assertRefused(String constraintName, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }
}
