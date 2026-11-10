package com.posturestudio.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// A payment account cannot exist wrong (guide 30, 4.1). Plain unit tests, no Spring
class PaymentAccountTest {

    private static final String KEY = "rk_test_abc123";
    private static final String WEBHOOK = "whsec_abc123";

    @ParameterizedTest
    @ValueSource(strings = {"rossi", "studio", "a", "studio2", "abcdefghijabcdefghijabcdefghij"})
    void validNamesAreAccepted(String name) {
        assertThat(new PaymentAccount(name, KEY, WEBHOOK).name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Rossi", "2studio", "studio-rossi", "studio_rossi", "rossi ", "abcdefghijabcdefghijabcdefghijk"})
    void invalidNamesAreRejected(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAccount(name, KEY, WEBHOOK))
                .withMessageContaining("not valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_test_abc", "sk_live_abc", "rk_test_abc", "rk_live_abc"})
    void secretAndRestrictedKeysAreAccepted(String key) {
        assertThat(new PaymentAccount("rossi", key, WEBHOOK).secretKey()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pk_test_abc", "sk_abc", "whsec_abc", "sk_test_ abc", "sk_prod_abc"})
    void otherKeysAreRejectedNamingTheVariableAndNeverTheValue(String key) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAccount("rossi", key, WEBHOOK))
                .withMessageContaining("STRIPE_ROSSI_SECRET_KEY")
                .withMessageNotContaining(key);
    }

    @Test
    void aMissingKeyNamesItsVariable() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAccount("rossi", null, WEBHOOK))
                .withMessage("STRIPE_ROSSI_SECRET_KEY is missing or empty");
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAccount("rossi", KEY, " "))
                .withMessage("STRIPE_ROSSI_WEBHOOK_SECRET is missing or empty");
    }

    @Test
    void aWebhookSecretMustBeOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentAccount("rossi", KEY, "sk_test_abc"))
                .withMessageContaining("STRIPE_ROSSI_WEBHOOK_SECRET")
                .withMessageNotContaining("sk_test_abc");
    }

    @Test
    void theModeComesFromTheKey() {
        assertThat(new PaymentAccount("rossi", "rk_test_abc", WEBHOOK).live()).isFalse();
        assertThat(new PaymentAccount("rossi", "sk_live_abc", WEBHOOK).live()).isTrue();
    }

    // the toString() a record generates would print the keys in any log line
    @Test
    void toStringShowsTheNameOnly() {
        String text = new PaymentAccount("rossi", "rk_test_secret1", "whsec_secret2").toString();

        assertThat(text).isEqualTo("PaymentAccount[rossi]");
    }
}
