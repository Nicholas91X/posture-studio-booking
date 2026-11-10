package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;

import org.junit.jupiter.api.Test;

// Reading the accounts from the configuration (guide 30, parts 3 and 4). The Environment is replaced by a
// Map: PaymentAccounts only needs "the value of this variable"
class PaymentAccountsTest {

    private static final Map<String, String> TEST_VARIABLES = Map.of(
            "STRIPE_ROSSI_SECRET_KEY", "rk_test_rossi",
            "STRIPE_ROSSI_WEBHOOK_SECRET", "whsec_rossi",
            "STRIPE_STUDIO_SECRET_KEY", "sk_test_studio",
            "STRIPE_STUDIO_WEBHOOK_SECRET", "whsec_studio");

    @Test
    void theAccountsAreReadInTheOrderOfTheList() {
        PaymentAccounts accounts = PaymentAccounts.from(" studio , rossi ", false, TEST_VARIABLES::get);

        assertThat(accounts.names()).containsExactly("studio", "rossi");
        assertThat(accounts.byName("rossi")).hasValueSatisfying(account -> {
            assertThat(account.secretKey()).isEqualTo("rk_test_rossi");
            assertThat(account.webhookSecret()).isEqualTo("whsec_rossi");
        });
        assertThat(accounts.isConfigured("studio")).isTrue();
        assertThat(accounts.isConfigured("bianchi")).isFalse();
        assertThat(accounts.byName("bianchi")).isEmpty();
    }

    // guide 30, 4.4: a developer without a Stripe account can still start the backend
    @Test
    void noAccountsIsAllowed() {
        assertThat(PaymentAccounts.from("", false, TEST_VARIABLES::get).names()).isEmpty();
        assertThat(PaymentAccounts.from(" , ", false, TEST_VARIABLES::get).names()).isEmpty();
        assertThat(PaymentAccounts.from(null, false, TEST_VARIABLES::get).names()).isEmpty();
    }

    @Test
    void anAccountListedTwiceStopsTheStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentAccounts.from("rossi,studio,rossi", false, TEST_VARIABLES::get))
                .withMessageContaining("'rossi' is listed twice");
    }

    @Test
    void anAccountWithoutItsVariablesStopsTheStartupNamingThem() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentAccounts.from("rossi,bianchi", false, TEST_VARIABLES::get))
                .withMessageContaining("STRIPE_BIANCHI_SECRET_KEY is missing or empty");
    }

    @Test
    void anInvalidNameStopsTheStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentAccounts.from("Rossi", false, TEST_VARIABLES::get))
                .withMessageContaining("'Rossi' is not valid");
    }

    // guide 30, 4.2: live keys on a laptop, or test keys in production, are refused in both directions
    @Test
    void aLiveKeyOutsideProductionStopsTheStartup() {
        Map<String, String> variables = Map.of(
                "STRIPE_ROSSI_SECRET_KEY", "sk_live_rossi",
                "STRIPE_ROSSI_WEBHOOK_SECRET", "whsec_rossi");

        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentAccounts.from("rossi", false, variables::get))
                .withMessageContaining("STRIPE_ROSSI_SECRET_KEY is a LIVE key")
                .withMessageNotContaining("sk_live_rossi");
        assertThat(PaymentAccounts.from("rossi", true, variables::get).names()).containsExactly("rossi");
    }

    @Test
    void aTestKeyInProductionStopsTheStartup() {
        assertThatIllegalStateException()
                .isThrownBy(() -> PaymentAccounts.from("rossi", true, TEST_VARIABLES::get))
                .withMessageContaining("STRIPE_ROSSI_SECRET_KEY is a TEST key");
    }

    @Test
    void theNamesCannotBeChangedFromOutside() {
        PaymentAccounts accounts = PaymentAccounts.from("rossi", false, TEST_VARIABLES::get);

        assertThat(accounts.names()).isUnmodifiable();
    }
}
