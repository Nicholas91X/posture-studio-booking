package com.posturestudio.booking.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.credit.CreditEvent;
import com.posturestudio.booking.credit.CreditEvent.PackagePurchased;
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;

/**
 * Which emails a credit event produces, and their text (guide 37, part 10). A pure function, as BookingEmails:
 * every case tested without Spring (CreditEmailsTest). Same rules: no name and no treatment in the subject,
 * dates in Italian.
 */
@Component
class CreditEmails {

    // a last valid day, e.g. "lunedì 8 febbraio 2027": a calendar day of Rome (D-069), no time and no zone
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN);

    private static final String FOOTER = """

            Questa è un'email automatica: per qualsiasi domanda, contatta lo studio.
            """;

    private final String appUrl;

    CreditEmails(@Value("${notification.app-url}") String appUrl) {
        this.appUrl = appUrl;
    }

    // No default: CreditEvent is sealed, so a new event stops this compiling until its emails are decided.
    // The customer gets the confirmation; the practitioner the notice of the sale (Nicholas, 2026-11-11), which
    // is the fallback of the push and so, as every email to the practitioner, never names the customer (D-032)
    List<OutgoingEmail> emailsFor(CreditEvent event, BookingFacts facts) {
        return switch (event) {
            case PackagePurchased purchased -> List.of(confirmation(purchased, facts), sold(purchased, facts));
        };
    }

    private OutgoingEmail sold(PackagePurchased purchased, BookingFacts facts) {
        return new OutgoingEmail(Recipient.PRACTITIONER, facts.practitioner().email(), "Pacchetto venduto", """
                Ciao %s,

                un cliente ha comprato un tuo pacchetto:

                    %d sedute di %s
                    valide fino a %s compreso

                Il pagamento è già incassato: non devi fare niente.

                Il pannello: %s
                """.formatted(facts.practitioner().firstName(), purchased.credits(), facts.serviceName(),
                DAY.format(purchased.validUntil()), appUrl) + FOOTER);
    }

    private OutgoingEmail confirmation(PackagePurchased purchased, BookingFacts facts) {
        return new OutgoingEmail(Recipient.CUSTOMER, facts.customer().email(), "Acquisto confermato", """
                Gentile %s,

                il tuo acquisto è confermato:

                    %d sedute di %s con %s %s

                Puoi usarle per prenotare fino a %s compreso.

                La ricevuta del pagamento ti arriva da Stripe.

                Per prenotare: %s
                """.formatted(facts.customer().firstName(), purchased.credits(), facts.serviceName(),
                facts.practitioner().firstName(), facts.practitioner().lastName(),
                DAY.format(purchased.validUntil()), appUrl) + FOOTER);
    }
}
