package com.posturestudio.booking.notification;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.posturestudio.booking.booking.BookingEvent;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.notification.OutgoingEmail.Recipient;
import com.posturestudio.booking.practitioner.PractitionerService;

/**
 * The emails of the booking events (guide 27). booking announces, this listens: booking does not know
 * that emails exist (D-039), and nobody depends on notification.
 *
 * @TransactionalEventListener: only after a successful commit, so a rolled back transaction sends nothing
 * (guide 20, part 4). @Async: not in the thread that committed, but in a virtual thread of its own
 * (NotificationConfiguration). The response and the jobs never wait for the mail server, and an error
 * here can never reach them: "a failed email does not undo the booking" holds by construction (guide 27, 3.5).
 *
 * What is lost: an email in memory when the application crashes, and an email the mail server refuses.
 * Both written in the log; the outbox is the way if that becomes a real problem (guide 27, 3.4).
 *
 * From piece 5 the push comes first (guide 28, part 8): when an event has one (only ApprovalRequested), it
 * goes to every device of the practitioner, and the email to the practitioner is its fallback (D-032): it
 * leaves only if no device took the push. The emails to the customer do not depend on it.
 */
@Component
class BookingNotifications {

    private static final Logger log = LoggerFactory.getLogger(BookingNotifications.class);

    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final CatalogService catalog;
    private final BookingEmails emails;
    private final PushMessages pushes;
    private final PushSender pushSender;
    private final Clock clock;
    // MailSender and not JavaMailSender: plain text needs only send(SimpleMailMessage) (guide 27, 8.3)
    private final MailSender mail;
    private final String from;

    BookingNotifications(CustomerService customers, PractitionerService practitioners, CatalogService catalog,
                         BookingEmails emails, PushMessages pushes, PushSender pushSender, Clock clock,
                         MailSender mail, @Value("${notification.mail.from}") String from) {
        this.customers = customers;
        this.practitioners = practitioners;
        this.catalog = catalog;
        this.emails = emails;
        this.pushes = pushes;
        this.pushSender = pushSender;
        this.clock = clock;
        this.mail = mail;
        this.from = from;
    }

    // public: Spring calls it through the proxy of @Async, which hands it to the executor and returns at once
    @Async(NotificationConfiguration.EXECUTOR)
    @TransactionalEventListener
    public void on(BookingEvent event) {
        String what = event.getClass().getSimpleName();
        List<OutgoingEmail> outgoing;
        try {
            outgoing = emails.emailsFor(event, factsOf(event));
        } catch (RuntimeException e) {
            log.error("No email for {} of booking {}: its data could not be read", what, event.bookingId(), e);
            return;
        }
        boolean pushed = pushed(event, what);
        // each email on its own: the practitioner's failing does not stop the customer's (guide 27, 7.1).
        // In the log the role of the recipient, never the address (guide 27, 7.2)
        for (OutgoingEmail email : outgoing) {
            if (pushed && email.recipient() == Recipient.PRACTITIONER) {
                // a device took the push: the fallback is not needed
                continue;
            }
            try {
                mail.send(messageOf(email));
            } catch (RuntimeException e) {
                log.error("Email to the {} for {} of booking {} not sent", email.recipient(), what, event.bookingId(), e);
            }
        }
    }

    // true only if at least one device of the practitioner took the push. Any failure, even a bug, means
    // false: the practitioner then gets the email, never nothing
    private boolean pushed(BookingEvent event, String what) {
        try {
            return pushes.pushFor(event, clock.instant())
                    .map(message -> pushSender.sendToPractitioner(event.practitionerId(), message))
                    .orElse(false);
        } catch (RuntimeException e) {
            log.error("No push for {} of booking {}: the email goes instead", what, event.bookingId(), e);
            return false;
        }
    }

    // Each module answers for its own piece, after the commit (guide 27, 5.4): three or four small queries,
    // each in a read-only transaction of its own, in a thread that holds no other
    private BookingFacts factsOf(BookingEvent event) {
        return new BookingFacts(
                customers.contactOf(event.customerId()),
                practitioners.contactOf(event.practitionerId()),
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
