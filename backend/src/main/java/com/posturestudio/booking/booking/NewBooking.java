package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * What the customer sends to ask for a slot: whom, what, when. The end is computed by the backend from
 * the duration of the service (D-005). startsAt with the offset, as every time in the APIs (D-035):
 * 2026-11-09T09:00:00+01:00.
 *
 * Wrappers, not primitives: a missing value arrives as null and is reported on its field (NotNull).
 */
public record NewBooking(
        @NotNull Long practitionerId,
        @NotNull Long serviceId,
        @NotNull OffsetDateTime startsAt) {
}
