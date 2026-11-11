package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.posturestudio.booking.credit.CreditEvent;
import com.posturestudio.booking.customer.CustomerContact;
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;
import com.posturestudio.booking.practitioner.PractitionerContact;

// The texts of the credit emails (guide 37, part 10): a pure function, no Spring
class CreditEmailsTest {

    private static final BookingFacts FACTS = new BookingFacts(
            new CustomerContact("maria@example.com", "Maria", "Bianchi"),
            new PractitionerContact("anna@example.com", "Anna", "Rossi"),
            "Seduta posturale");

    private final CreditEmails emails = new CreditEmails("http://localhost:3000");

    private static final CreditEvent.PackagePurchased PURCHASED =
            new CreditEvent.PackagePurchased(7, 1, 2, 3, 5, LocalDate.of(2027, 2, 8));

    @Test
    void aPackagePurchasedIsConfirmedToTheCustomer() {
        var sent = emails.emailsFor(PURCHASED, FACTS);

        assertThat(sent).filteredOn(email -> email.recipient() == Recipient.CUSTOMER).singleElement().satisfies(email -> {
            assertThat(email.recipient()).isEqualTo(Recipient.CUSTOMER);
            assertThat(email.to()).isEqualTo("maria@example.com");
            // no name, no treatment in the subject: it shows on a locked phone
            assertThat(email.subject()).isEqualTo("Acquisto confermato");
            assertThat(email.text())
                    .startsWith("Gentile Maria,")
                    .contains("5 sedute di Seduta posturale con Anna Rossi")
                    // the last valid day, in Italian, "included" written out (D-069)
                    .contains("fino a lunedì 8 febbraio 2027 compreso")
                    .contains("http://localhost:3000")
                    .contains("Questa è un'email automatica");
        });
    }

    // the notice of the sale (Nicholas, 2026-11-11): the fallback of the push, so never the name of the customer
    @Test
    void thePractitionerIsToldOfTheSaleWithoutTheNameOfTheCustomer() {
        var sent = emails.emailsFor(PURCHASED, FACTS);

        assertThat(sent).hasSize(2);
        assertThat(sent).filteredOn(email -> email.recipient() == Recipient.PRACTITIONER).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("anna@example.com");
            assertThat(email.subject()).isEqualTo("Pacchetto venduto");
            assertThat(email.text())
                    .startsWith("Ciao Anna,")
                    .contains("5 sedute di Seduta posturale")
                    .contains("valide fino a lunedì 8 febbraio 2027 compreso")
                    .contains("non devi fare niente")
                    .doesNotContain("Maria").doesNotContain("Bianchi");
        });
    }
    // ***** the concessions (guide 40, part 6): to the customer only, never the note *****

    @Test
    void aGiftIsToldToTheCustomer() {
        var sent = emails.emailsFor(new CreditEvent.CreditGranted(7, 1, 2, 3, 1, LocalDate.of(2027, 2, 8)), FACTS);

        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.recipient()).isEqualTo(Recipient.CUSTOMER);
            assertThat(email.subject()).isEqualTo("Sedute in omaggio");
            assertThat(email.text()).contains("hai ricevuto 1 seduta in omaggio")
                    .contains("Seduta posturale con Anna Rossi")
                    .contains("Puoi usarla per prenotare fino a lunedì 8 febbraio 2027 compreso");
        });
    }

    @Test
    void anExtensionIsToldWithBothDays() {
        var sent = emails.emailsFor(new CreditEvent.CreditExtended(7, 1, 2, 3, LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 3, 31)), FACTS);

        assertThat(sent).singleElement().satisfies(email -> assertThat(email.text())
                .contains("Prima potevi usarli fino a giovedì 31 dicembre 2026, ora fino a mercoledì 31 marzo 2027 compreso"));
    }

    @Test
    void aCorrectionIsToldWithWhatIsLeft() {
        var sent = emails.emailsFor(new CreditEvent.CreditAdjusted(7, 1, 2, 3, -1, 2, LocalDate.of(2026, 12, 31)), FACTS);

        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.subject()).isEqualTo("Crediti corretti");
            assertThat(email.text()).contains("tolte: 1 seduta").contains("Ora ne hai 2, da usare entro giovedì 31 dicembre 2026");
        });
    }
}
