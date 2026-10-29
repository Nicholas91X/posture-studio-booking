package com.posturestudio.booking.catalog;

/**
 * What the practitioner sends to create or change a package. The service is chosen at creation
 * and never changes, so it is not here. Price of the whole package, in integer cents (D-022).
 */
public record PackageOfferDetails(String name, int sessionsCount, long priceCents, int validityDays) {
}
