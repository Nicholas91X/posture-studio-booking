package com.posturestudio.booking.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What the practitioner sends to change a package. The service is chosen at creation
 * (NewPackageOffer) and never changes, so it is not here. Price of the whole package, in integer cents (D-022).
 * Wrappers instead of primitives: see ServiceOfferDetails.
 */
public record PackageOfferDetails(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(2) Integer sessionsCount,
        @NotNull @Positive Long priceCents,
        @NotNull @Positive Integer validityDays) {
}
