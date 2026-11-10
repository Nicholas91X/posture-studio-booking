package com.posturestudio.booking.notification;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.booking.BookingEvent;
import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.BookingEvent.LatePaymentReleased;
import com.posturestudio.booking.notification.PushMessage.PushPayload;
import com.posturestudio.booking.shared.StudioTime;

/**
 * Which events have a push, and its text (guide 28, parts 8 and 10). A pure function, like BookingEmails.
 *
 * A notification shows on a locked screen: date, time and deadline only. Never the customer's name (D-032),
 * never the kind of treatment (guide 27, 6.1).
 */
@Component
class PushMessages {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM, H:mm", Locale.ITALIAN);

    private final String appUrl;

    PushMessages(@Value("${notification.app-url}") String appUrl) {
        this.appUrl = appUrl;
    }

    // No default: a new event does not compile here until someone decides whether it has a push
    Optional<PushMessage> pushFor(BookingEvent event, Instant now) {
        return switch (event) {
            // the only event that asks the practitioner to do something, by a deadline (guide 28, 8.1)
            case ApprovalRequested requested -> Optional.of(new PushMessage(
                    new PushPayload("Nuova prenotazione da approvare",
                            when(requested.startsAt()) + " · rispondi entro " + when(requested.approvalDeadline()),
                            appUrl, "booking-" + requested.bookingId()),
                    untilDeadline(requested.approvalDeadline(), now)));
            case BookingConfirmed confirmed -> Optional.empty();
            case BookingRejected rejected -> Optional.empty();
            case ApprovalExpired expired -> Optional.empty();
            case BookingWithdrawn withdrawn -> Optional.empty();
            // the customer is told by email; nothing for the practitioner to do (guide 32, part 9)
            case LatePaymentReleased late -> Optional.empty();
        };
    }

    // after the deadline the request is closed, and a push saying "answer by 22:00" at 23:00 is noise:
    // the push service drops it by itself (guide 28, 8.3)
    private static Duration untilDeadline(Instant deadline, Instant now) {
        Duration left = Duration.between(now, deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static String when(Instant instant) {
        return WHEN.format(instant.atZone(StudioTime.ZONE));
    }
}
