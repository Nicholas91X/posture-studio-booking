package com.posturestudio.booking.notification;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.credit.CreditEvent;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;

/**
 * The emails of the credit events (guide 37, part 10), as BookingNotifications for the bookings: credit
 * announces, this listens after a successful commit, in a virtual thread of its own (D-053). A webhook whose
 * transaction is rolled back sends nothing; a failed email does not undo the lot.
 */
@Component
class CreditNotifications {

    private static final Logger log = LoggerFactory.getLogger(CreditNotifications.class);

    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final CatalogService catalog;
    private final CreditEmails emails;
    private final MailSender mail;
    private final String from;

    CreditNotifications(CustomerService customers, PractitionerService practitioners, CatalogService catalog,
                        CreditEmails emails, MailSender mail, @Value("${notification.mail.from}") String from) {
        this.customers = customers;
        this.practitioners = practitioners;
        this.catalog = catalog;
        this.emails = emails;
        this.mail = mail;
        this.from = from;
    }

    @Async(NotificationConfiguration.EXECUTOR)
    @TransactionalEventListener
    public void on(CreditEvent event) {
        String what = event.getClass().getSimpleName();
        List<OutgoingEmail> outgoing;
        try {
            outgoing = emails.emailsFor(event, factsOf(event));
        } catch (RuntimeException e) {
            log.error("No email for {} of lot {}: its data could not be read", what, event.lotId(), e);
            return;
        }
        // in the log the role of the recipient, never the address (guide 27, 7.2)
        for (OutgoingEmail email : outgoing) {
            try {
                mail.send(messageOf(email));
            } catch (RuntimeException e) {
                log.error("Email to the {} for {} of lot {} not sent", email.recipient(), what, event.lotId(), e);
            }
        }
    }

    // each module answers for its own piece, after the commit (guide 27, 5.4)
    private BookingFacts factsOf(CreditEvent event) {
        return new BookingFacts(customers.contactOf(event.customerId()), practitioners.contactOf(event.practitionerId()),
                catalog.serviceNameOf(event.serviceId()));
    }

    private SimpleMailMessage messageOf(OutgoingEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.text());
        return message;
    }
}
