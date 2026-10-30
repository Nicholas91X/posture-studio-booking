package com.posturestudio.booking.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request to create a package: the details plus the service, flat for the frontend.
 * Wrappers instead of primitives: see ServiceOfferDetails.
 */
public record NewPackageOffer(
        @NotNull Long serviceId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(2) Integer sessionsCount,
        @NotNull @Positive Long priceCents,
        @NotNull @Positive Integer validityDays) {

    PackageOfferDetails details() {
        return new PackageOfferDetails(name, sessionsCount, priceCents, validityDays);
    }
}
