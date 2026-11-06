package com.posturestudio.booking.practitioner;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.posturestudio.booking.shared.OneOf;

/**
 * The settings a practitioner changes from the panel; also what is read back.
 * The annotations check the shape of a request (400); the entity checks again.
 *
 * Wrappers, not primitives: with Jackson 3 a missing primitive fails the whole JSON reading
 * with a generic 400 that does not name the field. As a wrapper it arrives as null,
 * and validation reports it on its field (NotNull).
 *
 * slotStepMinutes: the panel shows it as a "half-hour starts" switch and sends 30 or 60 (D-033).
 * minBookingNoticeHours: customers book only slots starting more than N hours from now (D-030).
 * bookingHorizonDays: customers book at most N days ahead (D-023).
 */
public record PractitionerSettings(
        @NotNull @Positive Integer cancellationNoticeHours,
        @NotNull @Positive Integer singleCreditValidityDays,
        // same values as practitioner_slot_step_minutes_ck and Practitioner.SLOT_STEPS
        @NotNull @OneOf({30, 60}) Integer slotStepMinutes,
        @NotNull @Positive Integer minBookingNoticeHours,
        @NotNull @Positive Integer bookingHorizonDays) {
}
