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

    @Test
    void aPackagePurchasedIsConfirmedToTheCustomerOnly() {
        var purchased = new CreditEvent.PackagePurchased(7, 1, 2, 3, 5, LocalDate.of(2027, 2, 8));

        var sent = emails.emailsFor(purchased, FACTS);

        assertThat(sent).singleElement().satisfies(email -> {
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
}
