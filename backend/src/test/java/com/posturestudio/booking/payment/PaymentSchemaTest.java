package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The constraints of V10 (guide 30, parts 5 and 7), with plain SQL: it is the database that defends itself
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class PaymentSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private PaymentTestData data;
    private long practitioner;
    private long booking;

    @BeforeEach
    void rows() {
        data = new PaymentTestData(jdbc);
        practitioner = data.practitioner("anna@example.com");
        booking = data.booking(practitioner);
    }

    // ***** practitioner_payment_account *****

    @Test
    void aPractitionerHasOneAccountAtMost() {
        data.assign(practitioner, "alpha");

        assertViolates("practitioner_payment_account_pk", () -> data.assign(practitioner, "beta"));
    }

    @Test
    void anAssignedAccountNameMustBeWellFormed() {
        assertViolates("practitioner_payment_account_account_ck", () -> data.assign(practitioner, "Studio-Rossi"));
    }

    @Test
    void anAssignmentNeedsAnExistingPractitioner() {
        assertViolates("practitioner_payment_account_practitioner_fk", () -> data.assign(999_999, "alpha"));
    }

    // ***** payment *****

    @Test
    void aWellFormedPaymentIsAccepted() {
        long id = data.payment(booking, "alpha", "PENDING", "cs_test_1");

        assertThat(jdbc.queryForObject("select version from payment where id = ?", Long.class, id)).isZero();
    }

    @Test
    void theAccountOfAPaymentMustBeWellFormed() {
        assertViolates("payment_account_ck", () -> data.payment(booking, "ALPHA", "PENDING", "cs_test_1"));
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertViolates("payment_status_ck", () -> data.payment(booking, "alpha", "REFUNDED", "cs_test_1"));
    }

    @Test
    void theAmountMustBePositive() {
        assertViolates("payment_amount_cents_ck", () -> jdbc.update("""
                insert into payment (booking_id, account, amount_cents, status, stripe_checkout_session_id, checkout_url)
                values (?, 'alpha', 0, 'PENDING', 'cs_test_1', 'https://checkout.stripe.test/c/pay/cs_test_1')
                """, booking));
    }

    @Test
    void aCheckoutSessionBelongsToOnePayment() {
        data.payment(booking, "alpha", "EXPIRED", "cs_test_1");

        assertViolates("payment_checkout_session_uq", () -> data.payment(booking, "alpha", "EXPIRED", "cs_test_1"));
    }

    @Test
    void aPaymentIntentBelongsToOnePayment() {
        long first = data.payment(booking, "alpha", "EXPIRED", "cs_test_1");
        long second = data.payment(booking, "alpha", "EXPIRED", "cs_test_2");
        jdbc.update("update payment set stripe_payment_intent_id = 'pi_1' where id = ?", first);

        assertViolates("payment_payment_intent_uq",
                () -> jdbc.update("update payment set stripe_payment_intent_id = 'pi_1' where id = ?", second));
    }

    // the partial unique index (guide 30, 7.4): one PENDING per booking, any number of the others
    @Test
    void aBookingHasOnePendingPaymentAtMost() {
        data.payment(booking, "alpha", "PENDING", "cs_test_1");

        assertViolates("payment_one_pending_per_booking_uq", () -> data.payment(booking, "alpha", "PENDING", "cs_test_2"));
    }

    @Test
    void aBookingCanHaveManyExpiredPaymentsAndOnePendingBesides() {
        data.payment(booking, "alpha", "EXPIRED", "cs_test_1");
        data.payment(booking, "alpha", "EXPIRED", "cs_test_2");
        data.payment(booking, "alpha", "PENDING", "cs_test_3");

        assertThat(jdbc.queryForObject("select count(*) from payment where booking_id = ?", Long.class, booking))
                .isEqualTo(3);
    }

    @Test
    void pendingPaymentsOfDifferentBookingsAreIndependent() {
        long other = data.booking(practitioner);
        data.payment(booking, "alpha", "PENDING", "cs_test_1");

        data.payment(other, "alpha", "PENDING", "cs_test_2");
    }

    private void assertViolates(String constraintName, Runnable statement) {
        DataIntegrityViolationException e = Assertions.assertThrows(DataIntegrityViolationException.class, statement::run);
        assertThat(e.getMessage()).contains(constraintName);
    }
}
