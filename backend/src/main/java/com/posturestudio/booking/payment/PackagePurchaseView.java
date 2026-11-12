package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A purchase of a package, as the customer sees it (guide 37, part 5). paymentUrl and pageExpiresAt only while
 * the page can still be paid; lotId and validUntil only once it is paid. credits, validityDays and amountCents
 * are the photo of what the customer saw (D-069), whatever the package on sale has become since.
 */
record PackagePurchaseView(long id, long packageOfferId, PackagePurchaseStatus status, long amountCents,
                           int credits, int validityDays, @Schema(nullable = true) URI paymentUrl, @Schema(nullable = true) Instant pageExpiresAt, @Schema(nullable = true) Long lotId,
                           @Schema(nullable = true) LocalDate validUntil) {
}
