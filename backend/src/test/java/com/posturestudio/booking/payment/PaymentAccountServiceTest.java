package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

// The assignment of the accounts (guide 30, parts 5, 6, 8 and 9). The configured accounts are "alpha" and
// "beta", from the test configuration (src/test/resources/config/application.properties)
@DataJpaTest
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, PaymentConfiguration.class,
        PaymentAccountService.class, PractitionerService.class, AccountService.class})
public class PaymentAccountServiceTest {

    @Autowired
    PaymentAccountService service;

    @Autowired
    PaymentAccounts accounts;

    @Autowired
    PaymentRepository payments;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private PaymentTestData data;
    private long anna;

    @BeforeEach
    void setUp() {
        clock.reset();
        data = new PaymentTestData(jdbc);
        anna = data.practitioner("anna@example.com");
    }

    @Test
    void theTestConfigurationDeclaresTwoAccounts() {
        assertThat(accounts.names()).containsExactly("alpha", "beta");
    }

    // guide 30, part 6: no account, no card payments; piece 2 turns this into a 409
    @Test
    void aPractitionerWithoutAccountHasNoneForNewPayments() {
        assertThat(service.forNewPayment(anna)).isEmpty();
    }

    @Test
    void anAssignedAccountIsTheOneOfNewPayments() {
        service.assign(anna, "alpha");

        assertThat(service.forNewPayment(anna)).hasValueSatisfying(account -> assertThat(account.name()).isEqualTo("alpha"));
    }

    @Test
    void assigningTheSameAccountAgainChangesNothing() {
        AssignedAccountView first = service.assign(anna, "alpha");
        clock.advance(Duration.ofHours(1));

        AssignedAccountView again = service.assign(anna, "alpha");

        assertThat(again).isEqualTo(first);
    }

    @Test
    void reassigningMovesTheDateToo() {
        AssignedAccountView first = service.assign(anna, "alpha");
        clock.advance(Duration.ofHours(1));

        AssignedAccountView moved = service.assign(anna, "beta");

        assertThat(moved.account()).isEqualTo("beta");
        assertThat(moved.assignedAt()).isEqualTo(first.assignedAt().plus(Duration.ofHours(1)));
        assertThat(service.assignedTo(anna)).isEqualTo(moved);
    }

    @Test
    void anAccountNotConfiguredIsABusinessRule() {
        assertThatThrownBy(() -> service.assign(anna, "rossi"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PAYMENT_ACCOUNT_UNKNOWN));
        assertThat(service.forNewPayment(anna)).isEmpty();
    }

    @Test
    void anUnknownPractitionerIsNotFound() {
        assertThatThrownBy(() -> service.assign(999_999, "alpha")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.assignedTo(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPractitionerWithoutAccountIsNotFoundForTheAdmin() {
        assertThatThrownBy(() -> service.assignedTo(anna)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theListShowsEveryConfiguredAccountWithWhoUsesIt() {
        long marco = data.practitioner("marco@example.com");
        service.assign(marco, "alpha");
        service.assign(anna, "alpha");

        assertThat(service.listAccounts()).containsExactly(
                new PaymentAccountView("alpha", List.of(anna, marco)),
                new PaymentAccountView("beta", List.of()));
    }

    // D-057, the first half of the test of the brief (guide 30, part 8): a payment keeps the account it was
    // made on after the practitioner is moved. Capture and cancel on that account come with piece 4
    @Test
    void aPaymentKeepsItsAccountWhenThePractitionerIsReassigned() {
        service.assign(anna, "alpha");
        PaymentAccount atCreation = service.forNewPayment(anna).orElseThrow();
        Payment payment = payments.saveAndFlush(
                Payment.pending(data.booking(anna), atCreation, 3000, "cs_test_1",
                        java.net.URI.create("https://checkout.stripe.test/c/pay/cs_test_1"), clock.instant()));

        service.assign(anna, "beta");

        Payment reread = payments.findById(payment.getId()).orElseThrow();
        assertThat(reread.getAccount()).isEqualTo("alpha");
        assertThat(accounts.of(reread).name()).isEqualTo("alpha");
        assertThat(service.forNewPayment(anna).orElseThrow().name()).isEqualTo("beta");
        assertThat(jdbc.queryForObject("select account from payment where id = ?", String.class, payment.getId()))
                .isEqualTo("alpha");
    }
}
