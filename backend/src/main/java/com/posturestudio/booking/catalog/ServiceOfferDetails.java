package com.posturestudio.booking.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What the practitioner sends to create or change a service. Price in integer cents (D-022).
 * The annotations check the shape of a request (400); the entity checks again.
 *
 * Numbers are wrappers, not primitives: with Jackson 3 a missing primitive fails the whole
 * JSON reading with a generic 400 that does not name the field. As a wrapper it arrives as null,
 * and validation reports it on its field (NotNull).
 */
public record ServiceOfferDetails(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Integer durationMinutes,
        @NotNull @Positive Long priceCents) {
}
