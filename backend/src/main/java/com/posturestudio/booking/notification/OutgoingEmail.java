package com.posturestudio.booking.notification;

/**
 * An email ready to be sent: what BookingEmails decides, what BookingNotifications sends (guide 27, 8.1).
 * The recipient's role goes in the log when sending fails, never the address (guide 27, 7.2).
 */
record OutgoingEmail(Recipient recipient, String to, String subject, String text) {

    enum Recipient {
        CUSTOMER, PRACTITIONER
    }
}
