package com.posturestudio.booking.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.posturestudio.booking.booking.BookingEvent.BookingCancelled;
import com.posturestudio.booking.booking.PaidWith;

/**
 * A session to be paid in the studio was cancelled: its open payment link must not be paid any more (D-065,
 * guide 43, part 5). After the commit of the cancellation, outside its lock on the session; payment depends on
 * booking already, so listening to its event adds no dependency.
 *
 * A compensation that may fail: if Stripe does not answer, or anything else goes wrong, it is only logged. The
 * system stays right without it (the webhook releases a payment that arrives for a session no longer to be paid in
 * the studio); it is only less kind. Spring itself would not let the exception reach the caller: an AFTER_COMMIT
 * listener runs in afterCompletion, where Spring logs an exception as an ERROR with its stack and goes on (seen in
 * the defenses of guide 43, part 13). The catch is for the log: a warning that says what it means.
 *
 * Synchronous, in the thread of the request: the customer's answer waits for Stripe (at worst the timeouts of
 * D-061), but only for a session to be paid in the studio with a link still open.
 */
@Component
class PaymentLinkClosing {

    private static final Logger log = LoggerFactory.getLogger(PaymentLinkClosing.class);

    private final PaymentLinks links;

    PaymentLinkClosing(PaymentLinks links) {
        this.links = links;
    }

    @TransactionalEventListener
    public void on(BookingCancelled cancelled) {
        if (cancelled.paidWith() != PaidWith.IN_STUDIO) {
            // paid by card (a link already captured, or a request) or with a credit: no open link to close
            return;
        }
        try {
            links.closeAfterCancellation(cancelled.bookingId());
        } catch (RuntimeException e) {
            log.warn("The payment link of cancelled booking {} could not be closed: it expires by itself, and a payment"
                    + " that arrives anyway is released", cancelled.bookingId(), e);
        }
    }
}
