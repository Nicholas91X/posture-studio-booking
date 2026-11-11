package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.booking.LinkTarget;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerContact;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.StudioTime;

/**
 * The payment link of a session to be paid in the studio (D-059, D-065, guide 34). One valid link per session:
 * a new link makes the old Stripe page expire first. The amount is HELD, as for a request: the webhook captures
 * it only if the session still waits for it (guide 34, part 6).
 *
 * Locks (guide 34, part 8): only the PENDING payment of the session, never the session itself. The webhook of an
 * old link locks payment then session: locking the session here first could cross it in a deadlock.
 */
@Service
class PaymentLinks {

    private static final Logger log = LoggerFactory.getLogger(PaymentLinks.class);

    // A Stripe page lives LESS than 24 hours from ITS creation (D-061), and our clock may be a second ahead of
    // Stripe's (seen in the manual test of 2026-11-10: exactly 24 hours was refused). The same margin of 2 minutes
    // as the 32 minutes of the hold, on the other end. A link never outlives the start of the session either
    static final Duration MAXIMUM = Duration.ofHours(24).minusMinutes(2);

    private final BookingService bookings;
    private final PaymentAccountService accountService;
    private final PaymentRepository payments;
    private final PaymentAccounts accounts;
    private final StripeGateway stripe;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final PaymentLinkMessages messages;
    private final Clock clock;
    private final String returnBaseUrl;

    PaymentLinks(BookingService bookings, PaymentAccountService accountService, PaymentRepository payments,
                 PaymentAccounts accounts, StripeGateway stripe, CatalogService catalog, CustomerService customers,
                 PaymentLinkMessages messages, Clock clock, @Value("${payment.return-base-url}") String returnBaseUrl) {
        this.bookings = bookings;
        this.accountService = accountService;
        this.payments = payments;
        this.accounts = accounts;
        this.stripe = stripe;
        this.catalog = catalog;
        this.customers = customers;
        this.messages = messages;
        this.clock = clock;
        this.returnBaseUrl = returnBaseUrl.endsWith("/") ? returnBaseUrl.substring(0, returnBaseUrl.length() - 1)
                : returnBaseUrl;
    }

    @Transactional
    PaymentLinkView create(long practitionerId, long bookingId) {
        Instant now = clock.instant();
        // 404 if not theirs, 409 if not confirmed and to be paid in the studio, or starting too soon
        LinkTarget target = bookings.linkTarget(practitionerId, bookingId);
        PaymentAccount account = accountService.forNewPayment(practitionerId).orElseThrow(() -> new BusinessRuleException(
                ErrorCode.CARD_PAYMENTS_UNAVAILABLE, "Practitioner " + practitionerId + " cannot take card payments yet"));

        // one valid link per session: the old Stripe page goes first (guide 34, part 5)
        payments.findLockedByBookingIdAndStatus(bookingId, PaymentStatus.PENDING).ifPresent(this::invalidate);
        // The old payment must be EXPIRED in the database BEFORE the new PENDING is inserted: the insert of an
        // identity row runs at once, the update of the old one would wait for the commit, and the partial unique
        // index "one PENDING per booking" (V10) would refuse the new one
        payments.flush();

        Instant expiresAt = earlier(now.plus(MAXIMUM), target.startsAt());
        String serviceName = catalog.serviceNameOf(target.serviceId());
        CustomerContact customer = customers.contactOf(target.customerId());
        StripeGateway.CheckoutSession session = stripe.createCheckoutSession(account,
                new StripeGateway.BookingCheckout(bookingId, target.priceCents(), serviceName, customer.email(),
                        expiresAt, returnUrl(bookingId, "completato"), returnUrl(bookingId, "annullato")),
                // every press of the button is a new intention (guide 34, part 5)
                "booking-" + bookingId + "-link-" + UUID.randomUUID());
        payments.save(Payment.pending(PaymentSource.LINK, bookingId, account, target.priceCents(), session.id(),
                session.url(), now));
        return new PaymentLinkView(session.url(),
                messages.whatsapp(customers.phoneOf(target.customerId()), customer.firstName(), serviceName,
                        target.startsAt(), expiresAt, session.url()),
                StudioTime.inStudioZone(Instant.ofEpochSecond(expiresAt.getEpochSecond())));
    }

    // Read first (as D-064): an open page is made to expire on Stripe; an expired one is only closed here; a page
    // just PAID means the money is on its way, and no new link is made
    private void invalidate(Payment old) {
        PaymentAccount account = accounts.of(old);
        String sessionId = old.getStripeCheckoutSessionId();
        StripeGateway.CheckoutSessionStatus status = stripe.checkoutSessionStatus(account, sessionId);
        if (status == StripeGateway.CheckoutSessionStatus.OPEN
                && !stripe.expireCheckoutSession(account, sessionId, "expire-" + sessionId)) {
            // paid (or expired) between our read and our expire: read again
            status = stripe.checkoutSessionStatus(account, sessionId);
        }
        if (status == StripeGateway.CheckoutSessionStatus.COMPLETE) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_LINK_ALREADY_PAID,
                    "The link of booking " + old.getBookingId() + " was paid a moment ago");
        }
        old.expire();
        log.info("Payment link {} of booking {} invalidated by a new one", old.getId(), old.getBookingId());
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    // PLACEHOLDERS, as for the request (D-061): the pages arrive with step 12
    private URI returnUrl(long bookingId, String outcome) {
        return URI.create(returnBaseUrl + "/prenotazioni/" + bookingId + "?pagamento=" + outcome);
    }
}
