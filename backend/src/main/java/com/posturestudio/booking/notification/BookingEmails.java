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
import com.posturestudio.booking.booking.BookingEvent.BookingCancelled;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.BookingEvent.LatePaymentReleased;
import com.posturestudio.booking.booking.BookingStatus;
import com.posturestudio.booking.booking.PaidWith;
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
    // the last valid day of a credit: a day, no hour (D-069)
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALIAN);

    // cancelling a hold is immediate for us, but the bank may take days to show it (guide 27, 6.3)
    private static final String NOTHING_CHARGED = """
            Non ti è stato addebitato niente: il blocco sulla carta è stato annullato.
            La banca può impiegare qualche giorno a mostrarlo nell'estratto conto.""";

    // a request paid with a credit that went nowhere: the credit is back on its lot, with its last valid day
    // (guide 38, part 8)
    private static final String CREDIT_BACK = """
            Il credito che avevi usato è di nuovo disponibile, con la sua scadenza.""";

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
                    %s
                    Le tue prenotazioni: %s
                    """.formatted(details(facts, confirmed.startsAt()), creditUsed(confirmed.paidWith()), appUrl)));
            case BookingRejected rejected -> List.of(toCustomer(facts, "Prenotazione non confermata", rejected.startsAt(), """
                    la tua richiesta di prenotazione non è stata accettata:

                    %s

                    %s

                    Per scegliere un altro orario: %s
                    """.formatted(details(facts, rejected.startsAt()), nothingLost(rejected.paidWith()), appUrl)));
            case ApprovalExpired expired -> List.of(toCustomer(facts, "Prenotazione non confermata", expired.startsAt(), """
                    la tua richiesta di prenotazione non ha ricevuto risposta in tempo, ed è stata annullata:

                    %s

                    %s

                    Per scegliere un altro orario: %s
                    """.formatted(details(facts, expired.startsAt()), nothingLost(expired.paidWith()), appUrl)));
            case BookingWithdrawn withdrawn -> List.of(toCustomer(facts, "Richiesta ritirata", withdrawn.startsAt(), """
                    hai ritirato la tua richiesta di prenotazione:

                    %s

                    %s

                    Le tue prenotazioni: %s
                    """.formatted(details(facts, withdrawn.startsAt()), nothingLost(withdrawn.paidWith()), appUrl)));
            case BookingCancelled cancelled -> cancellation(cancelled, facts);
            case LatePaymentReleased late -> List.of(toCustomer(facts, "Pagamento arrivato in ritardo", late.startsAt(), """
                    il tuo pagamento è arrivato dopo la scadenza del tempo per pagare, e l'orario non era più
                    riservato a te:

                    %s

                    %s

                    Per scegliere un altro orario: %s
                    """.formatted(details(facts, late.startsAt()), NOTHING_CHARGED, appUrl)));
        };
    }

    // A confirmed session cancelled (guide 43, part 8). To the customer: what is left to them. To the practitioner,
    // only when the customer cancelled: the fallback of the push "Seduta cancellata" (D-054), never who
    private List<OutgoingEmail> cancellation(BookingCancelled cancelled, BookingFacts facts) {
        boolean byStudio = cancelled.status() == BookingStatus.CANCELLED_BY_STUDIO;
        OutgoingEmail toCustomer = toCustomer(facts, "Seduta cancellata", cancelled.startsAt(), """
                %s:

                %s

                %s

                Per prenotare un'altra seduta: %s
                """.formatted(byStudio ? "lo studio ha dovuto cancellare la tua seduta" : "hai cancellato la tua seduta",
                details(facts, cancelled.startsAt()), whatIsLeft(cancelled), appUrl));
        if (byStudio) {
            return List.of(toCustomer);
        }
        String text = """
                Ciao %s,

                una seduta è stata cancellata dal cliente %s:

                    %s
                    %s

                L'orario è di nuovo libero. I dettagli nel pannello: %s
                """.formatted(facts.practitioner().firstName(),
                cancelled.status() == BookingStatus.CANCELLED_IN_TIME ? "in tempo" : "oltre il preavviso",
                facts.serviceName(), when(cancelled.startsAt()), appUrl);
        return List.of(toCustomer, new OutgoingEmail(Recipient.PRACTITIONER, facts.practitioner().email(),
                subject("Seduta cancellata", cancelled.startsAt()), text + FOOTER));
    }

    // What the customer keeps (guide 43, part 4.1): a credit with its last day, nothing for a session that was not
    // paid, nothing for a late cancellation. A credit came back if and only if creditValidUntil is there
    private static String whatIsLeft(BookingCancelled cancelled) {
        String back = cancelled.creditValidUntil() == null ? null : """
                Ti abbiamo restituito un credito per questa seduta: puoi usarlo per prenotarne
                un'altra entro %s.""".formatted(DAY.format(cancelled.creditValidUntil()));
        // a switch expression: a new way of paying does not compile until someone writes what the customer is told
        return switch (cancelled.paidWith()) {
            case CREDIT -> back != null ? back : """
                    La cancellazione è arrivata oltre il preavviso dello studio: il credito usato per
                    questa seduta non torna disponibile.""";
            case CARD -> back != null ? back : """
                    La cancellazione è arrivata oltre il preavviso dello studio: la seduta pagata non
                    viene restituita come credito.""";
            case IN_STUDIO -> "La seduta era da pagare in studio: non c'è niente da restituire.";
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
                e %s.

                Per approvare o rifiutare, apri il pannello: %s
                """.formatted(facts.practitioner().firstName(), facts.serviceName(), when(requested.startsAt()),
                when(requested.approvalDeadline()), switch (requested.paidWith()) {
                    case CREDIT -> "il credito torna al cliente";
                    case CARD, IN_STUDIO -> "il blocco sulla carta del cliente si annulla";
                }, appUrl);
        return new OutgoingEmail(Recipient.PRACTITIONER, facts.practitioner().email(),
                subject("Nuova richiesta da approvare", requested.startsAt()), text + FOOTER);
    }

    // to the customer: paid but not booked yet. Money only held, an answer by the deadline, free to withdraw
    private OutgoingEmail requestSent(ApprovalRequested requested, BookingFacts facts) {
        return toCustomer(facts, "Richiesta di prenotazione inviata", requested.startsAt(), """
                la tua richiesta di prenotazione è stata inviata:

                %s

                Riceverai una risposta entro %s.

                %s

                Finché aspetti la risposta puoi ritirare la richiesta, senza costi: %s
                """.formatted(details(facts, requested.startsAt()), when(requested.approvalDeadline()),
                switch (requested.paidWith()) {
                    case CREDIT -> """
                            Hai usato un credito: è impegnato per questa richiesta. Se la richiesta
                            non viene approvata, torna disponibile.""";
                    case CARD, IN_STUDIO -> """
                            L'importo è bloccato sulla carta, non addebitato: si addebita solo se la richiesta
                            viene approvata. Altrimenti il blocco si annulla e non paghi niente.""";
                }, appUrl));
    }

    // A credit of the customer paid this session: say it, it is theirs, and from the panel it was spent by someone
    // else (guide 39, part 5). With a blank line after it; nothing at all otherwise
    private static String creditUsed(PaidWith paidWith) {
        return switch (paidWith) {
            case CREDIT -> "\nPer questa seduta è stato usato uno dei tuoi crediti.\n";
            case CARD, IN_STUDIO -> "";
        };
    }

    // what the customer did not lose when a request goes nowhere: a released hold, or a credit back. A switch
    // expression: a new way of paying does not compile until someone writes what the customer is told
    private static String nothingLost(PaidWith paidWith) {
        return switch (paidWith) {
            case CREDIT -> CREDIT_BACK;
            case CARD, IN_STUDIO -> NOTHING_CHARGED;
        };
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
