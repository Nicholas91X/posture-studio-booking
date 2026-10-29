package com.posturestudio.booking.catalog;

/**
 * What the practitioner sends to create or change a service. Price in integer cents (D-022).
 */
public record ServiceOfferDetails(String name, int durationMinutes, long priceCents) {
}
