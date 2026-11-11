package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.PackageOnSale;
import com.posturestudio.booking.credit.CreditService;
import com.posturestudio.booking.credit.PackageLot;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * A customer buys a package (step 10, piece 2, guide 37). The payment is born here, PENDING; the lot is born
 * in the webhook, when Stripe says the money arrived (D-007, StripeWebhookService.packagePaid).
 *
 * Dependencies: payment -> catalog (the package on sale), customer (the email for the receipt), credit (the
 * lots of the purchases, for the views).
 */
@Service
class PackagePurchases {

    private final CatalogService catalog;
    private final PaymentAccountService accountService;
    private final StripeGateway stripe;
    private final PaymentRepository payments;
    private final CustomerService customers;
    private final CreditService credits;
    private final Clock clock;
    private final String returnBaseUrl;

    PackagePurchases(CatalogService catalog, PaymentAccountService accountService, StripeGateway stripe,
                     PaymentRepository payments, CustomerService customers, CreditService credits, Clock clock,
                     @Value("${payment.return-base-url}") String returnBaseUrl) {
        this.catalog = catalog;
        this.accountService = accountService;
        this.stripe = stripe;
        this.payments = payments;
        this.customers = customers;
        this.credits = credits;
        this.clock = clock;
        this.returnBaseUrl = returnBaseUrl.endsWith("/") ? returnBaseUrl.substring(0, returnBaseUrl.length() - 1)
                : returnBaseUrl;
    }

    // Guide 37, part 2. In order: the package on sale (404), the account of its practitioner (409, BEFORE
    // Stripe), the Stripe page, our row with the photo, the commit. Stripe not answering: 503 and nothing is
    // left (D-061). The call runs in the transaction, but here it holds no lock anyone else waits for: no slot,
    // no customer row
    @Transactional
    public PackagePurchaseView start(long customerId, NewPackagePurchase request) {
        PackageOnSale offer = catalog.packageOnSale(request.packageOfferId());
        // read once, here, and copied into the payment (D-057)
        PaymentAccount account = accountService.forNewPayment(offer.practitionerId())
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.CARD_PAYMENTS_UNAVAILABLE,
                        "This practitioner does not take online payments yet"));
        Instant now = clock.instant();
        StripeGateway.CheckoutSession session = stripe.createCheckoutSession(account,
                new StripeGateway.PackageCheckout(offer.id(), offer.priceCents(),
                        offer.name() + " · " + offer.serviceName(),
                        customers.contactOf(customerId).email(),
                        now.plus(Payment.PACKAGE_PAGE_LIFE),
                        returnUrl("completato"),
                        returnUrl("annullato")),
                // a new key at every request (guide 37, part 4): it covers the retry of the library, which
                // reuses it; a double click makes two pages, one is paid and the other expires, harmless
                "package-" + customerId + "-" + offer.id() + "-" + UUID.randomUUID());
        Payment payment = payments.save(Payment.packagePending(customerId, offer.id(), account, offer.priceCents(),
                offer.sessionsCount(), offer.validityDays(), session.id(), session.url(), now));
        return viewOf(payment, null, now);
    }

    // "my purchases", the latest first: where the page back from Stripe asks how it went (guide 37, part 5)
    @Transactional(readOnly = true)
    public List<PackagePurchaseView> list(long customerId) {
        List<Payment> purchases = payments.findByCustomerIdAndPurposeOrderByIdDesc(customerId, PaymentPurpose.PACKAGE);
        Map<Long, PackageLot> lots = credits.packageLotsOf(purchases.stream().map(Payment::getId).toList());
        Instant now = clock.instant();
        return purchases.stream().map(payment -> viewOf(payment, lots.get(payment.getId()), now)).toList();
    }

    // the purchase of someone else is not found (404), as everything of another
    @Transactional(readOnly = true)
    public PackagePurchaseView find(long customerId, long purchaseId) {
        Payment payment = payments.findByIdAndCustomerIdAndPurpose(purchaseId, customerId, PaymentPurpose.PACKAGE)
                .orElseThrow(() -> new NotFoundException("Purchase " + purchaseId + " not found"));
        return viewOf(payment, credits.packageLotsOf(List.of(payment.getId())).get(payment.getId()), clock.instant());
    }

    // PENDING with its page only while the page can still be paid: by the clock, not by the arrival of the
    // notice of Stripe, which can be late (as paymentUrl of a booking, D-061).
    // A switch EXPRESSION on the enum: the compiler demands every value, so a new status of payment does not
    // compile until someone decides what the customer sees
    private static PackagePurchaseView viewOf(Payment payment, PackageLot lot, Instant now) {
        PackagePurchaseStatus status = switch (payment.getStatus()) {
            case SUCCEEDED -> PackagePurchaseStatus.PAID;
            case PENDING -> now.isBefore(payment.packagePageExpiresAt()) ? PackagePurchaseStatus.PENDING
                    : PackagePurchaseStatus.EXPIRED;
            case EXPIRED -> PackagePurchaseStatus.EXPIRED;
            // payment_purpose_columns_ck: a package is never held, never released
            case AUTHORIZED, CANCELED -> throw new IllegalStateException(
                    "Package payment " + payment.getId() + " is " + payment.getStatus());
        };
        boolean payable = status == PackagePurchaseStatus.PENDING;
        return new PackagePurchaseView(payment.getId(), payment.getPackageOfferId(), status, payment.getAmountCents(),
                payment.getPackageCredits(), payment.getPackageValidityDays(),
                payable ? payment.getCheckoutUrl() : null, payable ? payment.packagePageExpiresAt() : null,
                lot == null ? null : lot.lotId(), lot == null ? null : lot.validUntil());
    }

    // PLACEHOLDER until step 12: the page of the credits of the customer, which asks the list of purchases.
    // Coming back confirms nothing (D-007). No {CHECKOUT_SESSION_ID}: a java.net.URI cannot hold braces
    // (guide 37, part 5)
    private URI returnUrl(String outcome) {
        return URI.create(returnBaseUrl + "/crediti?acquisto=" + outcome);
    }
}
