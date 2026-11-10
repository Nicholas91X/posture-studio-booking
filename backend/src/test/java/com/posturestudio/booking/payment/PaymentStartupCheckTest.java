package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The startup check (guide 30, 4.3), called directly: a failed startup cannot be watched from inside a
// Spring context, the check can. Only the account "alpha" is configured here; the rows name others
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class PaymentStartupCheckTest {

    private static final PaymentAccounts ONLY_ALPHA = PaymentAccounts.from("alpha", false, Map.of(
            "STRIPE_ALPHA_SECRET_KEY", "rk_test_alpha",
            "STRIPE_ALPHA_WEBHOOK_SECRET", "whsec_alpha")::get);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentAccountAssignmentRepository assignments;

    @Autowired
    PaymentRepository payments;

    private PaymentTestData data;
    private PaymentStartupCheck check;

    @BeforeEach
    void setUp() {
        data = new PaymentTestData(jdbc);
        check = new PaymentStartupCheck(ONLY_ALPHA, assignments, payments);
    }

    @Test
    void anEmptyDatabaseStartsWithAnyConfiguration() {
        assertThatNoException().isThrownBy(check::check);
    }

    @Test
    void configuredAccountsStart() {
        long anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        data.payment(data.booking(anna), "alpha", "AUTHORIZED", "cs_test_1");

        assertThatNoException().isThrownBy(check::check);
    }

    @Test
    void anAssignedAccountThatIsNotConfiguredStopsTheStartup() {
        data.assign(data.practitioner("anna@example.com"), "rossi");

        assertThatIllegalStateException().isThrownBy(check::check)
                .withMessageContaining("not configured: rossi.");
    }

    // the case of guide 30, 4.3: "rossi" removed from .env while a session was still held on it
    @Test
    void theAccountOfAnOpenPaymentMustStayConfiguredEvenIfNobodyIsAssignedToIt() {
        long anna = data.practitioner("anna@example.com");
        data.assign(anna, "alpha");
        data.payment(data.booking(anna), "rossi", "AUTHORIZED", "cs_test_1");
        data.payment(data.booking(anna), "bianchi", "PENDING", "cs_test_2");

        // both names, sorted, in one message
        assertThatIllegalStateException().isThrownBy(check::check)
                .withMessageContaining("not configured: bianchi, rossi.");
    }

    @Test
    void closedPaymentsDoNotNeedTheirAccountAnyMore() {
        long anna = data.practitioner("anna@example.com");
        long booking = data.booking(anna);
        data.payment(booking, "rossi", "SUCCEEDED", "cs_test_1");
        data.payment(booking, "rossi", "CANCELED", "cs_test_2");
        data.payment(booking, "rossi", "EXPIRED", "cs_test_3");

        assertThatNoException().isThrownBy(check::check);
    }
}
