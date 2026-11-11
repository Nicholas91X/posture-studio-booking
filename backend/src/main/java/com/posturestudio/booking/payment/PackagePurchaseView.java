package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A purchase of a package, as the customer sees it (guide 37, part 5). paymentUrl and pageExpiresAt only while
 * the page can still be paid; lotId and validUntil only once it is paid. credits, validityDays and amountCents
 * are the photo of what the customer saw (D-069), whatever the package on sale has become since.
 */
record PackagePurchaseView(long id, long packageOfferId, PackagePurchaseStatus status, long amountCents,
                           int credits, int validityDays, URI paymentUrl, Instant pageExpiresAt, Long lotId,
                           LocalDate validUntil) {
}
