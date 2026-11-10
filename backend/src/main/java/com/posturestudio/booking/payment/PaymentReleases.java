package com.posturestudio.booking.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.ServiceUnavailableException;

/**
 * Releasing a card hold, and trying again when Stripe did not answer (D-064). Without the retries the customer's
 * card would keep the hold for up to 7 days because of a problem of ours: a few attempts with growing pauses
 * (Payment.RELEASE_RETRY_PAUSES), then the job gives up and logs it.
 *
 * Every attempt with a NEW idempotency key: Stripe keeps the response of a key for 24 hours, 500 errors included,
 * so the same key would get the same failure back. Releasing twice does no harm: the second time Stripe answers
 * "no longer cancelable", which is false here, and nothing changes.
 */
@Service
class PaymentReleases {

    private static final Logger log = LoggerFactory.getLogger(PaymentReleases.class);

    private final PaymentRepository payments;
    private final PaymentAccounts accounts;
    private final StripeGateway stripe;
    private final Clock clock;

    PaymentReleases(PaymentRepository payments, PaymentAccounts accounts, StripeGateway stripe, Clock clock) {
        this.payments = payments;
        this.accounts = accounts;
        this.stripe = stripe;
        this.clock = clock;
    }

    // One attempt on a payment ALREADY LOCKED by the caller, inside the caller's transaction. Never throws when
    // Stripe does not answer: it records the failure and when to try again. Not @Transactional: an exception out
    // of a transactional bean would mark the caller's transaction rollback-only (guide 33, part 4)
    void attempt(Payment payment, Instant now) {
        String paymentIntentId = payment.getStripePaymentIntentId();
        String key = payment.getReleaseAttempts() == 0 ? "cancel-" + paymentIntentId
                : "cancel-" + paymentIntentId + "-" + payment.getReleaseAttempts();
        try {
            // false: already canceled, or captured (guide 33, part 7): left as it is, Stripe's event settles it
            if (stripe.cancelPaymentIntent(accounts.of(payment), paymentIntentId, key)) {
                payment.cancel();
            } else {
                payment.releaseNotNeeded();
            }
        } catch (ServiceUnavailableException e) {
            if (payment.releaseFailed(now)) {
                log.error("Gave up releasing the hold of payment {} (booking {}) after {} attempts: it dissolves by"
                                + " itself within 7 days; to free the customer's card now, cancel {} by hand from the"
                                + " Stripe dashboard of account {}", payment.getId(), payment.getBookingId(),
                        payment.getReleaseAttempts(), paymentIntentId, payment.getAccount());
            } else {
                log.warn("Hold of payment {} (booking {}) not released: Stripe did not answer. Attempt {}, next at {}",
                        payment.getId(), payment.getBookingId(), payment.getReleaseAttempts(), payment.getNextReleaseAt());
            }
        }
    }

    // the payments owing a release whose time has come, for the job
    @Transactional(readOnly = true)
    List<Long> due() {
        return payments.idsOfReleasesDue(PaymentStatus.AUTHORIZED, clock.instant());
    }

    // One payment, in a transaction of its own, the guard read again AFTER the lock (as D-047): a webhook may have
    // released it meanwhile, or a second instance of the application may have just tried
    @Transactional
    void retry(long paymentId) {
        Instant now = clock.instant();
        Payment payment = payments.findLockedById(paymentId).orElse(null);
        if (payment == null || !payment.releaseDue(now)) {
            return;
        }
        attempt(payment, now);
    }
}
