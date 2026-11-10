package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.posturestudio.booking.auth.CodePurpose;

// The text of a code by SMS (guide 29, part 7): one SMS, no social engineering, autofill only on our site
class SmsTextsTest {

    // the basic GSM 03.38 alphabet, without the escape table: one character outside it makes the message
    // UCS-2, 70 characters per SMS instead of 160
    private static final String GSM_7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private final SmsTexts texts = new SmsTexts("https://studio.example");

    @Test
    void theSignInCodeReadsAsWritten() {
        assertThat(texts.text("042517", Duration.ofMinutes(10), CodePurpose.SIGN_IN)).isEqualTo("""
                Codice di accesso: 042517
                Vale 10 minuti. Non darlo a nessuno, nemmeno allo studio.

                @studio.example #042517""");
    }

    @Test
    void theVerificationCodeSaysWhatItIsFor() {
        assertThat(texts.text("042517", Duration.ofMinutes(10), CodePurpose.VERIFY_PHONE))
                .startsWith("Codice per verificare il numero: 042517\n")
                .endsWith("\n@studio.example #042517");
    }

    @ParameterizedTest
    @EnumSource(CodePurpose.class)
    void everyTextIsOneSmsOfTheBasicAlphabet(CodePurpose purpose) {
        String text = texts.text("999999", Duration.ofMinutes(10), purpose);

        assertThat(text.length()).isLessThanOrEqualTo(SmsTexts.MAX_LENGTH);
        assertThat(text.chars()).allMatch(c -> GSM_7.indexOf(c) >= 0);
    }

    // the origin-bound line has the host only, never the port of the development URL
    @Test
    void theAutofillLineHasTheHostOfTheSite() {
        assertThat(new SmsTexts("http://localhost:3000").text("123456", Duration.ofMinutes(10), CodePurpose.SIGN_IN))
                .endsWith("\n@localhost #123456");
    }
}
