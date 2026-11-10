package com.posturestudio.booking.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.booking.BookingEvent;
import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;
import com.posturestudio.booking.shared.StudioTime;

/**
 * Which emails an event produces, and their text (guide 27, parts 2 and 6). A pure function: it reads
 * nothing and sends nothing, so every case is tested without Spring (BookingEmailsTest), like FreeSlots.
 *
 * The rules of the texts:
 * - subjects show on a locked phone: the date, never a person's name or the kind of treatment;
 * - the practitioner's email never names the customer: it is also the fallback of the push (D-032), and
 *   the panel says who it is after the sign-in;
 * - dates in Rome and in Italian, whatever the zone and the language of the server.
 */
@Component
class BookingEmails {

    // immutable and thread-safe, unlike the old SimpleDateFormat: one constant for every thread
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, 'ore' H:mm", Locale.ITALIAN);
    private static final DateTimeFormatter WHEN_IN_SUBJECT =
            DateTimeFormatter.ofPattern("EEEE d MMMM, H:mm", Locale.ITALIAN);

    // cancelling a hold is immediate for us, but the bank may take days to show it (guide 27, 6.3)
    private static final String NOTHING_CHARGED = """
            Non ti è stato addebitato niente: il blocco sulla carta è stato annullato.
            La banca può impiegare qualche giorno a mostrarlo nell'estratto conto.""";

    private static final String FOOTER = """

            Questa è un'email automatica: per qualsiasi domanda, contatta lo studio.
            """;

    // the base address of the site; the exact pages come with steps 12 and 13 (guide 27, 6.6)
    private final String appUrl;

    BookingEmails(@Value("${notification.app-url}") String appUrl) {
        this.appUrl = appUrl;
    }

    // No default: BookingEvent is sealed, so this switch covers every event, and a new one (a cancellation,
    // step 11) stops it compiling until someone decides which emails it sends (guide 27, 8.2)
    List<OutgoingEmail> emailsFor(BookingEvent event, BookingFacts facts) {
        return switch (event) {
            case ApprovalRequested requested -> List.of(
                    toApprove(requested, facts),
                    requestSent(requested, facts));
            case BookingConfirmed confirmed -> List.of(toCustomer(facts, "Prenotazione confermata", confirmed.startsAt(), """
                    la tua prenotazione è confermata:

                    %s

                    Le tue prenotazioni: %s
                    """.formatted(details(facts, confirmed.startsAt()), appUrl)));
            case BookingRejected rejected -> List.of(toCustomer(facts, "Prenotazione non confermata", rejected.startsAt(), """
                    la tua richiesta di prenotazione non è stata accettata:

                    %s

                    %s

                    Per scegliere un altro orario: %s
                    """.formatted(details(facts, rejected.startsAt()), NOTHING_CHARGED, appUrl)));
            case ApprovalExpired expired -> List.of(toCustomer(facts, "Prenotazione non confermata", expired.startsAt(), """
                    la tua richiesta di prenotazione non ha ricevuto risposta in tempo, ed è stata annullata:

                    %s

                    %s

                    Per scegliere un altro orario: %s
                    """.formatted(details(facts, expired.startsAt()), NOTHING_CHARGED, appUrl)));
            case BookingWithdrawn withdrawn -> List.of(toCustomer(facts, "Richiesta ritirata", withdrawn.startsAt(), """
                    hai ritirato la tua richiesta di prenotazione:

                    %s

                    %s

                    Le tue prenotazioni: %s
                    """.formatted(details(facts, withdrawn.startsAt()), NOTHING_CHARGED, appUrl)));
        };
    }

    // to the practitioner: what, when, by when. Not who (D-032)
    private OutgoingEmail toApprove(ApprovalRequested requested, BookingFacts facts) {
        String text = """
                Ciao %s,

                hai una nuova richiesta di prenotazione da approvare:

                    %s
                    %s

                Rispondi entro %s. Dopo, la richiesta scade da sola
                e il blocco sulla carta del cliente si annulla.

                Per approvare o rifiutare, apri il pannello: %s
                """.formatted(facts.practitioner().firstName(), facts.serviceName(), when(requested.startsAt()),
                when(requested.approvalDeadline()), appUrl);
        return new OutgoingEmail(Recipient.PRACTITIONER, facts.practitioner().email(),
                subject("Nuova richiesta da approvare", requested.startsAt()), text + FOOTER);
    }

    // to the customer: paid but not booked yet. Money only held, an answer by the deadline, free to withdraw
    private OutgoingEmail requestSent(ApprovalRequested requested, BookingFacts facts) {
        return toCustomer(facts, "Richiesta di prenotazione inviata", requested.startsAt(), """
                la tua richiesta di prenotazione è stata inviata:

                %s

                Riceverai una risposta entro %s.

                L'importo è bloccato sulla carta, non addebitato: si addebita solo se la richiesta
                viene approvata. Altrimenti il blocco si annulla e non paghi niente.

                Finché aspetti la risposta puoi ritirare la richiesta, senza costi: %s
                """.formatted(details(facts, requested.startsAt()), when(requested.approvalDeadline()), appUrl));
    }

    private static OutgoingEmail toCustomer(BookingFacts facts, String subject, Instant startsAt, String body) {
        String text = "Gentile " + facts.customer().firstName() + ",\n\n" + body + FOOTER;
        return new OutgoingEmail(Recipient.CUSTOMER, facts.customer().email(), subject(subject, startsAt), text);
    }

    // e.g. "Prenotazione confermata: giovedì 12 novembre, 9:30"
    private static String subject(String what, Instant startsAt) {
        return what + ": " + WHEN_IN_SUBJECT.format(startsAt.atZone(StudioTime.ZONE));
    }

    // the two lines that say which session: in the customer's own mailbox, so with the practitioner's name
    private static String details(BookingFacts facts, Instant startsAt) {
        return "    " + facts.serviceName() + " con " + facts.practitioner().firstName() + " "
                + facts.practitioner().lastName() + "\n    " + when(startsAt);
    }

    // e.g. "giovedì 12 novembre 2026, ore 9:30": +01:00 or +02:00 chosen by the date (summer time)
    private static String when(Instant instant) {
        return WHEN.format(instant.atZone(StudioTime.ZONE));
    }
}
