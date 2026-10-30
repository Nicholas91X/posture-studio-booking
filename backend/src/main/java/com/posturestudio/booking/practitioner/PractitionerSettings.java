package com.posturestudio.booking.practitioner;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * The settings a practitioner changes from the panel; also what is read back.
 * The annotations check the shape of a request (400); the entity checks again.
 *
 * Wrappers, not primitives: with Jackson 3 a missing primitive fails the whole JSON reading
 * with a generic 400 that does not name the field. As a wrapper it arrives as null,
 * and validation reports it on its field (NotNull).
 */
public record PractitionerSettings(
        @NotNull @Positive Integer cancellationNoticeHours,
        @NotNull @Positive Integer singleCreditValidityDays,
        @NotNull @Positive Integer slotStepMinutes) {
}
