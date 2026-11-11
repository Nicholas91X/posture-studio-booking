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
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;
import com.posturestudio.booking.practitioner.PractitionerService;

/**
 * The emails of the credit events (guide 37, part 10), as BookingNotifications for the bookings: credit
 * announces, this listens after a successful commit, in a virtual thread of its own (D-053). A webhook whose
 * transaction is rolled back sends nothing; a failed email does not undo the lot.
 *
 * As for a request to approve (D-032, D-054): the push to the practitioner comes first, and the email to the
 * practitioner leaves only if no device took it. The email to the customer does not depend on it.
 */
@Component
class CreditNotifications {

    private static final Logger log = LoggerFactory.getLogger(CreditNotifications.class);

    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final CatalogService catalog;
    private final CreditEmails emails;
    private final PushMessages pushes;
    private final PushSender pushSender;
    private final MailSender mail;
    private final String from;

    CreditNotifications(CustomerService customers, PractitionerService practitioners, CatalogService catalog,
                        CreditEmails emails, PushMessages pushes, PushSender pushSender, MailSender mail,
                        @Value("${notification.mail.from}") String from) {
        this.customers = customers;
        this.practitioners = practitioners;
        this.catalog = catalog;
        this.emails = emails;
        this.pushes = pushes;
        this.pushSender = pushSender;
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
        boolean pushed = pushed(event, what);
        // in the log the role of the recipient, never the address (guide 27, 7.2)
        for (OutgoingEmail email : outgoing) {
            if (pushed && email.recipient() == Recipient.PRACTITIONER) {
                // a device took the push: the fallback is not needed
                continue;
            }
            try {
                mail.send(messageOf(email));
            } catch (RuntimeException e) {
                log.error("Email to the {} for {} of lot {} not sent", email.recipient(), what, event.lotId(), e);
            }
        }
    }

    // true only if at least one device took the push. Any failure means false: the practitioner then gets the
    // email, never nothing (as BookingNotifications)
    private boolean pushed(CreditEvent event, String what) {
        try {
            return pushes.pushFor(event)
                    .map(message -> pushSender.sendToPractitioner(event.practitionerId(), message))
                    .orElse(false);
        } catch (RuntimeException e) {
            log.error("No push for {} of lot {}: the email goes instead", what, event.lotId(), e);
            return false;
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
