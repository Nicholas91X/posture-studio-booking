package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.posturestudio.booking.booking.BookingEvent;
import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.PaidWith;
import com.posturestudio.booking.booking.BookingEvent.LatePaymentReleased;
import com.posturestudio.booking.customer.CustomerContact;
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;
import com.posturestudio.booking.practitioner.PractitionerContact;

// The texts of the booking emails (guide 27, parts 2 and 6): a pure function, so no Spring and no threads.
// Thursday 12 November 2026, 9:30 in Rome is 08:30 UTC (winter time, +01:00)
class BookingEmailsTest {

    private static final String APP = "https://studio.example";
    private static final Instant THURSDAY_930 = Instant.parse("2026-11-12T08:30:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-11T20:30:00Z");

    private final BookingEmails emails = new BookingEmails(APP);
    private final BookingFacts facts = new BookingFacts(
            new CustomerContact("maria@example.com", "Maria", "Bianchi"),
            new PractitionerContact("anna@example.com", "Anna", "Rossi"),
            "Valutazione posturale");

    @Test
    void aPaidRequestWritesToThePractitionerAndToTheCustomer() {
        List<OutgoingEmail> sent = emails.emailsFor(new ApprovalRequested(1, 2, 3, 4, THURSDAY_930, DEADLINE, PaidWith.CARD), facts);

        assertThat(sent).extracting(OutgoingEmail::recipient, OutgoingEmail::to).containsExactly(
                tuple(Recipient.PRACTITIONER, "anna@example.com"),
                tuple(Recipient.CUSTOMER, "maria@example.com"));

        OutgoingEmail toAnna = sent.get(0);
        assertThat(toAnna.subject()).isEqualTo("Nuova richiesta da approvare: giovedì 12 novembre, 9:30");
        assertThat(toAnna.text()).startsWith("Ciao Anna,")
                .contains("Valutazione posturale", "giovedì 12 novembre 2026, ore 9:30")
                .contains("Rispondi entro mercoledì 11 novembre 2026, ore 21:30")
                .contains("apri il pannello: " + APP);

        OutgoingEmail toMaria = sent.get(1);
        assertThat(toMaria.subject()).isEqualTo("Richiesta di prenotazione inviata: giovedì 12 novembre, 9:30");
        assertThat(toMaria.text()).startsWith("Gentile Maria,")
                .contains("Valutazione posturale con Anna Rossi")
                .contains("Riceverai una risposta entro mercoledì 11 novembre 2026, ore 21:30")
                .contains("bloccato sulla carta, non addebitato")
                .contains("puoi ritirare la richiesta, senza costi: " + APP);
    }

    // D-032: this email is also the fallback of the push, and the panel says who it is after the sign-in
    @Test
    void thePractitionerIsNeverToldTheNameOfTheCustomer() {
        OutgoingEmail toAnna = emails.emailsFor(new ApprovalRequested(1, 2, 3, 4, THURSDAY_930, DEADLINE, PaidWith.CARD), facts).get(0);

        assertThat(toAnna.text()).doesNotContain("Maria").doesNotContain("Bianchi");
    }

    // the whole text once, as it will look: blank lines, indentation, footer
    @Test
    void theConfirmationReadsAsWritten() {
        List<OutgoingEmail> sent = emails.emailsFor(new BookingConfirmed(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD), facts);

        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.recipient()).isEqualTo(Recipient.CUSTOMER);
            assertThat(email.to()).isEqualTo("maria@example.com");
            assertThat(email.subject()).isEqualTo("Prenotazione confermata: giovedì 12 novembre, 9:30");
            assertThat(email.text()).isEqualTo("""
                    Gentile Maria,

                    la tua prenotazione è confermata:

                        Valutazione posturale con Anna Rossi
                        giovedì 12 novembre 2026, ore 9:30

                    Le tue prenotazioni: https://studio.example

                    Questa è un'email automatica: per qualsiasi domanda, contatta lo studio.
                    """);
        });
    }

    // ***** paid with a credit (guide 38, part 8): never a word about a card *****

    // guide 39, part 5: a credit of the customer was spent, maybe by someone else from the panel: say it
    @Test
    void aConfirmationWithACreditSaysACreditWasUsed() {
        OutgoingEmail email = emails.emailsFor(new BookingConfirmed(1, 2, 3, 4, THURSDAY_930, PaidWith.CREDIT), facts)
                .getFirst();

        assertThat(email.text()).contains("Per questa seduta è stato usato uno dei tuoi crediti.");
        assertThat(emails.emailsFor(new BookingConfirmed(1, 2, 3, 4, THURSDAY_930, PaidWith.IN_STUDIO), facts)
                .getFirst().text()).doesNotContain("crediti");
        assertThat(emails.emailsFor(new BookingConfirmed(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD), facts)
                .getFirst().text()).doesNotContain("crediti");
    }

    @Test
    void aRequestWithACreditSaysTheCreditIsCommitted() {
        List<OutgoingEmail> sent = emails.emailsFor(
                new ApprovalRequested(1, 2, 3, 4, THURSDAY_930, DEADLINE, PaidWith.CREDIT), facts);

        assertThat(sent.get(0).text()).contains("e il credito torna al cliente").doesNotContain("carta");
        assertThat(sent.get(1).text()).contains("Hai usato un credito: è impegnato per questa richiesta")
                .contains("torna disponibile").doesNotContain("carta");
    }

    @Test
    void aRequestWithACreditThatGoesNowhereGivesTheCreditBack() {
        List<OutgoingEmail> sent = List.of(
                emails.emailsFor(new BookingRejected(1, 2, 3, 4, THURSDAY_930, PaidWith.CREDIT), facts).getFirst(),
                emails.emailsFor(new ApprovalExpired(1, 2, 3, 4, THURSDAY_930, PaidWith.CREDIT), facts).getFirst(),
                emails.emailsFor(new BookingWithdrawn(1, 2, 3, 4, THURSDAY_930, PaidWith.CREDIT), facts).getFirst());

        assertThat(sent).allSatisfy(email -> assertThat(email.text())
                .contains("Il credito che avevi usato è di nuovo disponibile, con la sua scadenza.")
                .doesNotContain("carta").doesNotContain("addebitato"));
    }

    @Test
    void aRejectionSaysNothingWasCharged() {
        assertThat(emails.emailsFor(new BookingRejected(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD), facts)).singleElement()
                .satisfies(email -> {
                    assertThat(email.to()).isEqualTo("maria@example.com");
                    assertThat(email.subject()).isEqualTo("Prenotazione non confermata: giovedì 12 novembre, 9:30");
                    assertThat(email.text()).contains("non è stata accettata", "Non ti è stato addebitato niente",
                            "Per scegliere un altro orario: " + APP);
                });
    }

    @Test
    void anExpiryGivesItsOwnReason() {
        assertThat(emails.emailsFor(new ApprovalExpired(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD), facts)).singleElement()
                .satisfies(email -> {
                    assertThat(email.to()).isEqualTo("maria@example.com");
                    assertThat(email.subject()).isEqualTo("Prenotazione non confermata: giovedì 12 novembre, 9:30");
                    assertThat(email.text()).contains("non ha ricevuto risposta in tempo", "Non ti è stato addebitato niente");
                });
    }

    // guide 32, part 9: the customer saw "paid" on Stripe's page, and must learn that nothing was charged
    @Test
    void aLatePaymentSaysTheSlotWasGoneAndNothingWasCharged() {
        assertThat(emails.emailsFor(new LatePaymentReleased(1, 2, 3, 4, THURSDAY_930), facts)).singleElement()
                .satisfies(email -> {
                    assertThat(email.to()).isEqualTo("maria@example.com");
                    assertThat(email.subject()).isEqualTo("Pagamento arrivato in ritardo: giovedì 12 novembre, 9:30");
                    assertThat(email.text()).contains("dopo la scadenza del tempo per pagare",
                            "Non ti è stato addebitato niente", "Per scegliere un altro orario");
                });
    }

    @Test
    void aWithdrawalIsAReceipt() {
        assertThat(emails.emailsFor(new BookingWithdrawn(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD), facts)).singleElement()
                .satisfies(email -> {
                    assertThat(email.to()).isEqualTo("maria@example.com");
                    assertThat(email.subject()).isEqualTo("Richiesta ritirata: giovedì 12 novembre, 9:30");
                    assertThat(email.text()).contains("hai ritirato la tua richiesta", "Non ti è stato addebitato niente");
                });
    }

    // Thursday 9 July 2026, 9:30 in Rome is 07:30 UTC: summer time, +02:00, chosen by the date
    @Test
    void summerTimeIsTheStudiosToo() {
        Instant julyNineThirty = Instant.parse("2026-07-09T07:30:00Z");

        OutgoingEmail email = emails.emailsFor(new BookingConfirmed(1, 2, 3, 4, julyNineThirty, PaidWith.CARD), facts).getFirst();

        assertThat(email.subject()).isEqualTo("Prenotazione confermata: giovedì 9 luglio, 9:30");
        assertThat(email.text()).contains("giovedì 9 luglio 2026, ore 9:30");
    }

    // subjects show on a locked phone (guide 27, 6.1): no person's name, no kind of treatment
    @Test
    void noSubjectNamesAPersonOrTheTreatment() {
        Stream<BookingEvent> every = Stream.of(
                new ApprovalRequested(1, 2, 3, 4, THURSDAY_930, DEADLINE, PaidWith.CARD),
                new BookingConfirmed(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD),
                new BookingRejected(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD),
                new ApprovalExpired(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD),
                new BookingWithdrawn(1, 2, 3, 4, THURSDAY_930, PaidWith.CARD),
                new LatePaymentReleased(1, 2, 3, 4, THURSDAY_930));

        List<String> subjects = every.flatMap(event -> emails.emailsFor(event, facts).stream())
                .map(OutgoingEmail::subject)
                .toList();

        assertThat(subjects).hasSize(7).allSatisfy(subject -> assertThat(subject)
                .doesNotContain("Maria", "Bianchi", "Anna", "Rossi", "Valutazione"));
    }
}
