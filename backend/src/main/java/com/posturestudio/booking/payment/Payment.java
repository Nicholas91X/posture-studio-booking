package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
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
        Objects.requireNonNull(account, "account must not be null");
        Payment payment = new Payment();
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

    @Column
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(updatable = false)
    private String stripeCheckoutSessionId;

    // the Stripe page, kept so that the customer finds it again (V11, guide 31, part 9)
    @Column(updatable = false)
    private String checkoutUrl;

    @Column
    private String stripePaymentIntentId;

    // the second defense, as in booking: the transitions lock the booking row first (guide 30, 7.5)
    @Version
    private long version;

    // from the Clock, as booking.created_at
    @Column(updatable = false)
    private Instant createdAt;

    Long getId() {
        return id;
    }

    Long getBookingId() {
        return bookingId;
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
