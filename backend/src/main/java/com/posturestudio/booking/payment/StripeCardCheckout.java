package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.CardCheckout;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;

/**
 * booking's CardCheckout with Stripe Checkout (guide 31). The order is the one of guide 31, part 4: first the
 * session on Stripe (it can be abandoned: nobody knows its address until we commit), then our row PENDING,
 * in the transaction of the request.
 *
 * Dependencies: payment -> booking (this interface), catalog (the name of the service on the page),
 * customer (the email for the receipt; never the name: it is not needed, guide 31, part 6).
 */
@Service
class StripeCardCheckout implements CardCheckout {

    private final PaymentAccountService accountService;
    private final StripeGateway stripe;
    private final PaymentRepository payments;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final Clock clock;
    private final String returnBaseUrl;

    StripeCardCheckout(PaymentAccountService accountService, StripeGateway stripe, PaymentRepository payments,
                       CatalogService catalog, CustomerService customers, Clock clock,
                       @Value("${payment.return-base-url}") String returnBaseUrl) {
        this.accountService = accountService;
        this.stripe = stripe;
        this.payments = payments;
        this.catalog = catalog;
        this.customers = customers;
        this.clock = clock;
        this.returnBaseUrl = returnBaseUrl.endsWith("/") ? returnBaseUrl.substring(0, returnBaseUrl.length() - 1)
                : returnBaseUrl;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean available(long practitionerId) {
        return accountService.forNewPayment(practitionerId).isPresent();
    }

    // Joins the transaction of the request (REQUIRED, the default): if anything here throws, the booking is
    // rolled back too
    @Override
    @Transactional
    public URI start(CheckoutRequest request) {
        // read once, here, and copied into the payment (D-057): every later operation uses this account
        PaymentAccount account = accountService.forNewPayment(request.practitionerId())
                .orElseThrow(() -> new IllegalStateException("Practitioner " + request.practitionerId()
                        + " has no payment account: available() should have been asked first"));
        StripeGateway.CheckoutSession session = stripe.createCheckoutSession(account,
                new StripeGateway.BookingCheckout(request.bookingId(), request.amountCents(),
                        catalog.serviceNameOf(request.serviceId()),
                        customers.contactOf(request.customerId()).email(),
                        request.expiresAt(),
                        returnUrl(request.bookingId(), "completato"),
                        returnUrl(request.bookingId(), "annullato")),
                // one session per request of a customer, and a booking id never comes back (guide 31, part 7)
                "booking-" + request.bookingId() + "-checkout");
        payments.save(Payment.pending(request.bookingId(), account, request.amountCents(), session.id(),
                session.url(), clock.instant()));
        return session.url();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<URI> pageOf(long bookingId) {
        return payments.findByBookingIdAndStatus(bookingId, PaymentStatus.PENDING).map(Payment::getCheckoutUrl);
    }

    // PLACEHOLDERS: the pages back from Stripe arrive with step 12 (guide 31, part 6). Coming back confirms
    // nothing (D-007): the page shows "payment being checked" and asks the backend for the status
    private URI returnUrl(long bookingId, String outcome) {
        return URI.create(returnBaseUrl + "/prenotazioni/" + bookingId + "?pagamento=" + outcome);
    }
}
