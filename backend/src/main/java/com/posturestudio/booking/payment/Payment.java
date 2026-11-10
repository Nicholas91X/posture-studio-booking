package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.posturestudio.booking.shared.Checks;

// A payment of a session through Stripe Checkout (V10, guide 30, part 7). Created at piece 2, moved by the
// webhook (piece 3) and by capture and cancel (piece 4)
@Entity
@Table(name = "payment")
class Payment {

    protected Payment() {
        // JPA needs a no-arg constructor
    }

    // Born when Stripe has answered with the Checkout session (guide 30, 7.3). It takes the ACCOUNT, not a
    // name: only a configured account can be copied here, and from now on it never changes (D-057)
    static Payment pending(long bookingId, PaymentAccount account, long amountCents, String checkoutSessionId,
                           URI checkoutUrl, Instant now) {
        return pending(PaymentSource.REQUEST, bookingId, account, amountCents, checkoutSessionId, checkoutUrl, now);
    }

    static Payment pending(PaymentSource source, long bookingId, PaymentAccount account, long amountCents,
                           String checkoutSessionId, URI checkoutUrl, Instant now) {
        Objects.requireNonNull(account, "account must not be null");
        Payment payment = new Payment();
        payment.purpose = PaymentPurpose.BOOKING;
        payment.source = Objects.requireNonNull(source, "source must not be null");
        payment.bookingId = bookingId;
        payment.account = account.name();
        payment.amountCents = Checks.requirePositive(amountCents, "amountCents");
        payment.status = PaymentStatus.PENDING;
        payment.stripeCheckoutSessionId = Checks.requireNotBlank(checkoutSessionId, "checkoutSessionId");
        payment.checkoutUrl = Objects.requireNonNull(checkoutUrl, "checkoutUrl must not be null").toString();
        payment.createdAt = Objects.requireNonNull(now, "now must not be null");
        return payment;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // booking belongs to another module: only the id (D-026)
    @Column(updatable = false)
    private Long bookingId;

    // the account the payment was made on: never updatable, whatever the admin reassigns later (D-057)
    @Column(updatable = false)
    private String account;

    // copied from the booking (D-011)
    @Column(updatable = false)
    private long amountCents;

    // a session or a package (V15, D-069); the package columns arrive with piece 2 of step 10
    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private PaymentPurpose purpose;

    // a customer's request, or a payment link (V14, D-065)
    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private PaymentSource source;

    @Column
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(updatable = false)
    private String stripeCheckoutSessionId;

    // the Stripe page, kept so that the customer finds it again (V11, guide 31, part 9)
    @Column(updatable = false)
    private String checkoutUrl;

    // once set by authorize, never changed: JPA writes it with the status
    @Column
    private String stripePaymentIntentId;

    // a release that failed because Stripe did not answer (V13, D-064): how many times, and when to try again
    @Column
    private int releaseAttempts;

    @Column
    private Instant nextReleaseAt;

    // the second defense, as in booking: the transitions lock the booking row first (guide 30, 7.5)
    @Version
    private long version;

    // from the Clock, as booking.created_at
    @Column(updatable = false)
    private Instant createdAt;

    // ***** TRANSITIONS (guide 33, part 9) *****
    // Every one says whether it changed anything: false is "already done, or not for me", never an error.
    // Called on a payment read WITH ITS LOCK (select ... for update): a second transaction waits, then reads the
    // state the first saved, and gets false. The same compare-and-set of D-062, without a JPQL update that
    // would leave this object stale in memory

    // The customer paid: the amount is held (checkout.session.completed). From EXPIRED too (D-065, guide 34,
    // part 6): Stripe completes only sessions that were open, so a "completed" for a payment we had already closed
    // (a link invalidated while the customer was paying) means money really held on the card, to be judged like
    // any other; ignoring it would leave a hold nobody releases
    boolean authorize(String paymentIntentId) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.EXPIRED) {
            return false;
        }
        this.stripePaymentIntentId = Checks.requireNotBlank(paymentIntentId, "paymentIntentId");
        this.status = PaymentStatus.AUTHORIZED;
        return true;
    }

    // the page expired unpaid (checkout.session.expired)
    boolean expire() {
        return move(PaymentStatus.PENDING, PaymentStatus.EXPIRED);
    }

    // the held amount was captured: at approval, or found by the reconciliation (payment_intent.succeeded)
    boolean capture() {
        return move(PaymentStatus.AUTHORIZED, PaymentStatus.SUCCEEDED);
    }

    // the hold was released: by us, or by Stripe after 7 days (payment_intent.canceled)
    boolean cancel() {
        return move(PaymentStatus.AUTHORIZED, PaymentStatus.CANCELED);
    }

    // ***** RELEASES THAT FAILED (D-064) *****

    // The pauses before each new attempt: 1 minute, 5, 30, 2 hours. After the fifth failure (about 2 hours and a
    // half from the first) the job gives up: the hold dissolves by itself within 7 days
    static final List<Duration> RELEASE_RETRY_PAUSES =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));

    // Stripe did not answer a release. true: given up, no more attempts
    boolean releaseFailed(Instant now) {
        releaseAttempts++;
        if (releaseAttempts > RELEASE_RETRY_PAUSES.size()) {
            nextReleaseAt = null;
            return true;
        }
        nextReleaseAt = now.plus(RELEASE_RETRY_PAUSES.get(releaseAttempts - 1));
        return false;
    }

    // a release is owed and its time has come
    boolean releaseDue(Instant now) {
        return status == PaymentStatus.AUTHORIZED && nextReleaseAt != null && !now.isBefore(nextReleaseAt);
    }

    // no longer cancelable (already canceled, or captured): nothing more to try, Stripe's event settles it
    void releaseNotNeeded() {
        nextReleaseAt = null;
    }

    int getReleaseAttempts() {
        return releaseAttempts;
    }

    Instant getNextReleaseAt() {
        return nextReleaseAt;
    }

    private boolean move(PaymentStatus from, PaymentStatus to) {
        if (status != from) {
            return false;
        }
        this.status = to;
        // out of AUTHORIZED no release is owed any more (check payment_next_release_ck, V13)
        this.nextReleaseAt = null;
        return true;
    }

    Long getId() {
        return id;
    }

    Long getBookingId() {
        return bookingId;
    }

    PaymentSource getSource() {
        return source;
    }

    String getAccount() {
        return account;
    }

    long getAmountCents() {
        return amountCents;
    }

    PaymentStatus getStatus() {
        return status;
    }

    String getStripeCheckoutSessionId() {
        return stripeCheckoutSessionId;
    }

    URI getCheckoutUrl() {
        return URI.create(checkoutUrl);
    }

    String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
