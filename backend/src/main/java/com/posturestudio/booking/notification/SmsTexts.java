package com.posturestudio.booking.notification;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.auth.CodePurpose;

/**
 * The text of a code by SMS (guide 29, part 7). A pure function, as BookingEmails, for every provider.
 *
 * - One SMS: at most 160 characters of the basic GSM-7 alphabet. One character outside it (many accented
 *   letters, any emoji) moves the whole message to a coding of 70 characters per SMS: two or three SMS, two
 *   or three times the price. So no accents: SmsTextsTest checks length and characters.
 * - "Non darlo a nessuno, nemmeno allo studio": against whoever phones pretending to be the studio and asks
 *   for "the code you just received".
 * - The last line is an origin-bound one-time code (@host #code): Chrome on Android and Safari on iPhone
 *   offer to fill the code in by themselves, only on the site with that host. A fake site gets nothing.
 * - No names, nothing about health: a locked phone shows it.
 */
@Component
class SmsTexts {

    static final int MAX_LENGTH = 160;

    private final String host;

    SmsTexts(@Value("${notification.app-url}") String appUrl) {
        this.host = URI.create(appUrl).getHost();
    }

    String text(String code, Duration validity, CodePurpose purpose) {
        String first = switch (purpose) {
            case SIGN_IN -> "Codice di accesso: ";
            case VERIFY_PHONE -> "Codice per verificare il numero: ";
        };
        return first + code + "\n"
                + "Vale " + validity.toMinutes() + " minuti. Non darlo a nessuno, nemmeno allo studio.\n"
                + "\n"
                + "@" + host + " #" + code;
    }
}
