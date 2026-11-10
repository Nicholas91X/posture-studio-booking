package com.posturestudio.booking.payment;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.booking.PaymentOutcome;

/**
 * What a verified Stripe event does (D-062, guide 32). One transaction per event, with the "seen" row inside
 * it: if anything fails, everything rolls back, the controller answers 500, and Stripe sends the event again,
 * which is then new again (guide 32, 5.1 and part 8).
 *
 * Two levels against "at least once" (guide 32, part 5): the event id (SeenStripeEvents), and every change of
 * a payment written as compare-and-set, checked BEFORE the booking is asked anything (5.2).
 */
@Service
class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final SeenStripeEvents seen;
    private final PaymentRepository payments;
    private final PaymentAccounts accounts;
    private final BookingService bookings;
    private final StripeGateway stripe;
    private final Clock clock;

    StripeWebhookService(SeenStripeEvents seen, PaymentRepository payments, PaymentAccounts accounts,
                         BookingService bookings, StripeGateway stripe, Clock clock) {
        this.seen = seen;
        this.payments = payments;
        this.accounts = accounts;
        this.bookings = bookings;
        this.stripe = stripe;
        this.clock = clock;
    }

    // account: the one whose endpoint received the event, its signature already verified
    @Transactional
    public void handle(PaymentAccount account, StripeNotice notice, String type) {
        if (notice instanceof StripeNotice.Ignored) {
            return;
        }
        if (!seen.firstTime(notice.eventId(), account.name(), type, clock.instant())) {
            log.info("Stripe event {} already processed: ignored", notice.eventId());
            return;
        }
        // no default: a new kind of notice stops this compiling until it is decided here
        switch (notice) {
            case StripeNotice.SessionCompleted completed -> sessionCompleted(account, completed);
            case StripeNotice.SessionExpired expired -> sessionExpired(account, expired);
            case StripeNotice.Ignored ignored -> { }
        }
    }

    private void sessionCompleted(PaymentAccount account, StripeNotice.SessionCompleted completed) {
        Payment payment = paymentOf(account, completed.sessionId(), completed.eventId());
        if (payment == null) {
            return;
        }
        if (!"complete".equals(completed.status()) || completed.paymentIntentId() == null) {
            log.warn("Stripe event {}: session {} completed without a held payment (status {})",
                    completed.eventId(), completed.sessionId(), completed.status());
            return;
        }
        // FIRST the payment, then the booking (guide 32, 5.2): a second event for the same session stops here.
        // If it went on, the booking (already PENDING_APPROVAL) would answer NotWanted, and a valid hold would
        // be released
        if (payments.authorize(payment.getId(), completed.paymentIntentId(), PaymentStatus.PENDING,
                PaymentStatus.AUTHORIZED) == 0) {
            log.info("Stripe event {}: payment {} already past PENDING, nothing to do", completed.eventId(), payment.getId());
            return;
        }
        switch (bookings.paymentAuthorized(payment.getBookingId())) {
            case PaymentOutcome.Accepted accepted ->
                    log.info("Payment {} held, booking {} is {}", payment.getId(), payment.getBookingId(), accepted.status());
            case PaymentOutcome.NotWanted notWanted -> release(payment, completed.paymentIntentId(), notWanted);
        }
    }

    // THIS PaymentIntent, on the account of the payment (D-057). A failure throws: rollback, 500, Stripe retries
    private void release(Payment payment, String paymentIntentId, PaymentOutcome.NotWanted notWanted) {
        stripe.cancelPaymentIntent(accounts.of(payment), paymentIntentId, "cancel-" + paymentIntentId);
        payments.move(payment.getId(), PaymentStatus.AUTHORIZED, PaymentStatus.CANCELED);
        log.info("Payment {} released: booking {} is {} and does not want it", payment.getId(), payment.getBookingId(),
                notWanted.status());
    }

    // The booking is left to the job, which closes expired holds within a minute (D-047): one path less
    private void sessionExpired(PaymentAccount account, StripeNotice.SessionExpired expired) {
        Payment payment = paymentOf(account, expired.sessionId(), expired.eventId());
        if (payment != null) {
            payments.move(payment.getId(), PaymentStatus.PENDING, PaymentStatus.EXPIRED);
        }
    }

    // null, logged, when the session is not ours or belongs to another account (guide 32, part 4)
    private Payment payment(String sessionId) {
        return payments.findByStripeCheckoutSessionId(sessionId).orElse(null);
    }

    private Payment paymentOf(PaymentAccount account, String sessionId, String eventId) {
        Payment payment = sessionId == null ? null : payment(sessionId);
        if (payment == null) {
            // e.g. a session made by hand in the dashboard of that account: nothing of ours
            log.warn("Stripe event {}: no payment for session {}", eventId, sessionId);
            return null;
        }
        if (!payment.getAccount().equals(account.name())) {
            // a misconfiguration: the endpoint of one account received an event about another's payment
            log.error("Stripe event {} received on account {} about payment {} of account {}: ignored", eventId,
                    account.name(), payment.getId(), payment.getAccount());
            return null;
        }
        return payment;
    }
}
