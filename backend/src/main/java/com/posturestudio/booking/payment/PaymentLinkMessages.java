package com.posturestudio.booking.payment;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.shared.StudioTime;

/**
 * The WhatsApp message of a payment link (guide 34, part 9), composed here and not by the frontend: it depends on
 * data of the backend (the service, the time in the studio's zone, the expiry of the link). A pure function, as
 * BookingEmails: nothing sent from here, the practitioner sends it from their phone.
 */
@Component
class PaymentLinkMessages {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM, 'ore' H:mm", Locale.ITALIAN);

    // https://wa.me/<number>?text=... with the number in international form WITHOUT the "+" (393331234567);
    // with no number, https://wa.me/?text=... and the practitioner picks the contact
    URI whatsapp(Optional<String> phone, String firstName, String serviceName, Instant startsAt, Instant expiresAt,
                 URI paymentUrl) {
        String text = "Ciao %s, ecco il link per pagare la seduta \"%s\" di %s: %s Il link vale fino a %s."
                .formatted(firstName, serviceName, when(startsAt), paymentUrl, when(expiresAt));
        String number = phone.map(e164 -> e164.startsWith("+") ? e164.substring(1) : e164).orElse("");
        return URI.create("https://wa.me/" + number + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                .replace("+", "%20"));
    }

    private static String when(Instant instant) {
        return WHEN.format(StudioTime.inStudioZone(instant));
    }
}
