package com.posturestudio.booking.payment;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.booking.CardHolds;
import com.posturestudio.booking.booking.PaymentOutcome;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.PackageOwner;
import com.posturestudio.booking.credit.CreditService;
import com.posturestudio.booking.credit.PackageLot;
import com.posturestudio.booking.credit.PackagePurchase;

/**
 * What a verified Stripe event does (D-062, D-063, guides 32 and 33). One transaction per event, with the "seen"
 * row inside it: if anything fails, everything rolls back, the controller answers with an error, and Stripe sends
 * the event again, which is then new again (guide 32, 5.1 and part 8).
 *
 * Two levels against "at least once" (guide 32, part 5): the event id (SeenStripeEvents), and every change of a
 * payment made on the payment read WITH ITS LOCK, by a method that says whether it changed anything, BEFORE the
 * booking is asked anything (guide 33, part 9: the JPQL updates of piece 3 left the object stale in memory).
 * Lock order: the payment first, then the booking (guide 33, part 8).
 */
@Service
class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final SeenStripeEvents seen;
    private final PaymentRepository payments;
    private final PaymentAccounts accounts;
    private final BookingService bookings;
    private final StripeGateway stripe;
    private final StripeCardHolds cards;
    private final CatalogService catalog;
    private final CreditService credits;
    private final Clock clock;

    StripeWebhookService(SeenStripeEvents seen, PaymentRepository payments, PaymentAccounts accounts,
                         BookingService bookings, StripeGateway stripe, StripeCardHolds cards, CatalogService catalog,
                         CreditService credits, Clock clock) {
        this.seen = seen;
        this.payments = payments;
        this.accounts = accounts;
        this.bookings = bookings;
        this.stripe = stripe;
        this.cards = cards;
        this.catalog = catalog;
        this.credits = credits;
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
            case StripeNotice.PaymentIntentSucceeded succeeded -> paymentIntentSucceeded(account, succeeded);
            case StripeNotice.PaymentIntentCanceled canceled -> paymentIntentCanceled(account, canceled);
            case StripeNotice.Ignored ignored -> { }
        }
    }

    private void sessionCompleted(PaymentAccount account, StripeNotice.SessionCompleted completed) {
        if (!"complete".equals(completed.status()) || completed.paymentIntentId() == null) {
            log.warn("Stripe event {}: session {} completed without a held payment (status {})",
                    completed.eventId(), completed.sessionId(), completed.status());
            return;
        }
        Payment payment = ours(account, completed.sessionId() == null ? null
                : payments.findLockedByStripeCheckoutSessionId(completed.sessionId()).orElse(null), completed.eventId());
        if (payment == null) {
            return;
        }
        // The purpose decides the path (guide 37, part 7). A switch STATEMENT on an enum is not checked for
        // exhaustiveness by the compiler (only a switch expression, or one with patterns, is): a third purpose
        // would fall through in silence, so the two cases are written out and nothing else is expected
        switch (payment.getPurpose()) {
            case BOOKING -> sessionPaid(payment, completed);
            case PACKAGE -> packagePaid(payment, completed);
        }
    }

    // A session paid: the amount is held (D-031)
    private void sessionPaid(Payment payment, StripeNotice.SessionCompleted completed) {
        // FIRST the payment, then the booking (guide 32, 5.2): a second event for the same session stops here.
        // If it went on, the booking (already PENDING_APPROVAL) would answer NotWanted, and a valid hold would
        // be released
        if (!payment.authorize(completed.paymentIntentId())) {
            log.info("Stripe event {}: payment {} already past PENDING, nothing to do", completed.eventId(), payment.getId());
            return;
        }
        // the source decides the path (V14, D-065): a customer's request waits for its approval; a payment link
        // is captured at once if the session still waits for it
        switch (payment.getSource()) {
            case REQUEST -> {
                switch (bookings.paymentAuthorized(payment.getBookingId())) {
                    case PaymentOutcome.Accepted accepted -> log.info("Payment {} held, booking {} is {}",
                            payment.getId(), payment.getBookingId(), accepted.status());
                    case PaymentOutcome.NotWanted notWanted -> release(payment, notWanted);
                }
            }
            case LINK -> linkPaid(payment);
        }
    }

    // A package paid (guide 37, part 7): captured at once, so the money is the studio's already. The lot is born
    // from the PHOTO in the payment (D-069), never from the package on sale, which may have changed or been
    // deactivated since: the customer paid what they saw. Once only: the payment moves PENDING -> SUCCEEDED on
    // its locked row, so a second event for the same session stops here; the same event twice was already
    // stopped by SeenStripeEvents; and credit_lot_payment_uq refuses a second lot if a bug got past both
    private void packagePaid(Payment payment, StripeNotice.SessionCompleted completed) {
        if (!"paid".equals(completed.paymentStatus())) {
            // with cards only it does not happen; complete and not paid must never create credits
            log.warn("Stripe event {}: package payment {} completed but not paid ({}): no credits",
                    completed.eventId(), payment.getId(), completed.paymentStatus());
            return;
        }
        if (!payment.paid(completed.paymentIntentId())) {
            log.info("Stripe event {}: package payment {} already paid, nothing to do", completed.eventId(),
                    payment.getId());
            return;
        }
        PackageOwner owner = catalog.packageOwnerOf(payment.getPackageOfferId());
        PackageLot lot = credits.lotFromPackage(new PackagePurchase(payment.getId(), payment.getCustomerId(),
                owner.practitionerId(), owner.serviceId(), payment.getPackageOfferId(), payment.getAmountCents(),
                payment.getPackageCredits(), payment.getPackageValidityDays(), completed.occurredAt()));
        log.info("Package payment {} paid: lot {} with {} credits, valid until {}", payment.getId(), lot.lotId(),
                lot.credits(), lot.validUntil());
    }

    // A payment link paid (guide 34, part 6). The session is locked by linkPaymentArrived until our commit: two
    // links paid together are decided one after the other, and the first one that finds the session still to be
    // paid in the studio wins; every later one is released (D-059, guide 34, part 7).
    // THIS PaymentIntent is captured, not "the" held payment of the booking: there may be two
    private void linkPaid(Payment payment) {
        switch (bookings.linkPaymentArrived(payment.getBookingId())) {
            case PaymentOutcome.Accepted accepted -> {
                // capture first, then change (D-063). Stripe not answering throws: rollback, Stripe retries
                switch (cards.captureLocked(payment)) {
                    case CardHolds.CaptureResult.Captured captured -> {
                        bookings.paidByLink(payment.getBookingId());
                        log.info("Payment link {} captured: booking {} is now paid by card", payment.getId(),
                                payment.getBookingId());
                    }
                    case CardHolds.CaptureResult.Refused refused ->
                            log.warn("Payment link {} of booking {} not captured ({}): the session stays to be paid"
                                    + " in the studio", payment.getId(), payment.getBookingId(), refused.reason());
                }
            }
            case PaymentOutcome.NotWanted notWanted -> release(payment, notWanted);
        }
    }

    // THIS PaymentIntent, on the account of the payment (D-057). A failure throws: rollback, error, Stripe retries
    private void release(Payment payment, PaymentOutcome.NotWanted notWanted) {
        String paymentIntentId = payment.getStripePaymentIntentId();
        // false: no longer cancelable; the payment stays AUTHORIZED until Stripe's own event says which
        if (stripe.cancelPaymentIntent(accounts.of(payment), paymentIntentId, "cancel-" + paymentIntentId)) {
            payment.cancel();
        }
        log.info("Payment {} released: booking {} is {} and does not want it", payment.getId(), payment.getBookingId(),
                notWanted.status());
    }

    // The booking is left to the job, which closes expired holds within a minute (D-047): one path less
    private void sessionExpired(PaymentAccount account, StripeNotice.SessionExpired expired) {
        Payment payment = ours(account, expired.sessionId() == null ? null
                : payments.findLockedByStripeCheckoutSessionId(expired.sessionId()).orElse(null), expired.eventId());
        if (payment != null) {
            payment.expire();
        }
    }

    // The reconciliation (guide 33, part 6). Normally our commit came first and the payment is SUCCEEDED: false,
    // nothing to do. AUTHORIZED means Stripe captured and our commit failed: confirm, without capturing again
    private void paymentIntentSucceeded(PaymentAccount account, StripeNotice.PaymentIntentSucceeded succeeded) {
        Payment payment = byPaymentIntent(account, succeeded.paymentIntentId(), succeeded.eventId());
        if (payment == null || !payment.capture()) {
            return;
        }
        log.warn("Stripe event {}: payment {} was captured but not recorded (a commit failed): reconciled",
                succeeded.eventId(), payment.getId());
        switch (bookings.paymentCaptured(payment.getBookingId())) {
            case PaymentOutcome.Accepted accepted ->
                    log.info("Booking {} confirmed by the reconciliation", payment.getBookingId());
            case PaymentOutcome.NotWanted notWanted ->
                    log.error("Payment {} was CAPTURED for booking {}, which is {}: refund it by hand from the Stripe"
                            + " dashboard of account {} (D-015)", payment.getId(), payment.getBookingId(),
                            notWanted.status(), payment.getAccount());
        }
    }

    // a hold released by Stripe after 7 days, or after a release of ours that Stripe did not answer (guide 33,
    // part 7). After our own successful releases the payment is already CANCELED: false, nothing to do
    private void paymentIntentCanceled(PaymentAccount account, StripeNotice.PaymentIntentCanceled canceled) {
        Payment payment = byPaymentIntent(account, canceled.paymentIntentId(), canceled.eventId());
        if (payment != null && payment.cancel()) {
            log.info("Stripe event {}: hold of payment {} released by Stripe", canceled.eventId(), payment.getId());
        }
    }

    // A PaymentIntent we do not know: a payment of the dashboard, or one whose session event has not been
    // processed yet (it will be: the session event carries the PaymentIntent too)
    private Payment byPaymentIntent(PaymentAccount account, String paymentIntentId, String eventId) {
        return ours(account, paymentIntentId == null ? null
                : payments.findLockedByStripePaymentIntentId(paymentIntentId).orElse(null), eventId);
    }

    // null, logged, when the payment is not ours or belongs to another account (guide 32, part 4)
    private Payment ours(PaymentAccount account, Payment payment, String eventId) {
        if (payment == null) {
            log.warn("Stripe event {}: no payment of ours", eventId);
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
