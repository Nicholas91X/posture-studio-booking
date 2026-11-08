package com.posturestudio.booking.practitioner;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.posturestudio.booking.shared.OneOf;

/**
 * The settings a practitioner changes from the panel; also what is read back.
 * The annotations check the shape of a request (400); the entity checks again.
 *
 * Wrappers, not primitives: with Jackson 3 a missing primitive fails the whole JSON reading
 * with a generic 400 that does not name the field. As a wrapper it arrives as null,
 * and validation reports it on its field (NotNull). An unknown bookingApproval ("MAYBE") still
 * fails the reading with a generic 400: accepted, the panel sends it from a switch (D-042).
 *
 * slotStepMinutes: the panel shows it as a "half-hour starts" switch and sends 30 or 60 (D-033).
 * minBookingNoticeHours: customers book only slots starting more than N hours from now (D-030).
 * bookingHorizonDays: customers book at most N days ahead (D-023).
 * maxFutureBookings: open future bookings per customer with this practitioner (D-023, D-041).
 * bookingApproval and approvalWindowHours: who confirms, and how long the practitioner has to answer (D-030).
 */
@PractitionerSettings.WindowWithinNotice
public record PractitionerSettings(
        @NotNull @Positive Integer cancellationNoticeHours,
        @NotNull @Positive Integer singleCreditValidityDays,
        // same values as practitioner_slot_step_minutes_ck and Practitioner.SLOT_STEPS
        @NotNull @OneOf({30, 60}) Integer slotStepMinutes,
        @NotNull @Positive Integer minBookingNoticeHours,
        @NotNull @Positive Integer bookingHorizonDays,
        @NotNull @Positive Integer maxFutureBookings,
        @NotNull BookingApproval bookingApproval,
        // same limit as practitioner_approval_window_hours_ck and Practitioner.MAX_APPROVAL_WINDOW_HOURS
        @NotNull @Positive @Max(48) Integer approvalWindowHours) {

    /**
     * Class-level: the approval window is not longer than the minimum booking notice, so the answer
     * always comes before the session starts (D-030). Reported on the field approvalWindowHours,
     * whichever of the two the practitioner changed.
     */
    @Documented
    @Constraint(validatedBy = WindowWithinNotice.Validator.class)
    @Target(TYPE)
    @Retention(RUNTIME)
    public @interface WindowWithinNotice {

        String message() default "must not exceed minBookingNoticeHours";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        // created by Hibernate Validator through reflection, so public
        class Validator implements ConstraintValidator<WindowWithinNotice, PractitionerSettings> {

            @Override
            public boolean isValid(PractitionerSettings settings, ConstraintValidatorContext context) {
                // missing values are left to NotNull
                if (settings == null || settings.approvalWindowHours() == null || settings.minBookingNoticeHours() == null
                        || settings.approvalWindowHours() <= settings.minBookingNoticeHours()) {
                    return true;
                }
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("approvalWindowHours")
                        .addConstraintViolation();
                return false;
            }
        }
    }
}
