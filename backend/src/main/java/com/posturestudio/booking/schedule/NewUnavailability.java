package com.posturestudio.booking.schedule;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.OffsetDateTime;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What the panel sends to create an absence. Times with their offset, e.g.
 * 2026-11-12T11:15:00+01:00 (D-035): stored as instants.
 */
@NewUnavailability.EndsAfterStart
public record NewUnavailability(
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        // the same limit as Unavailability.MAX_REASON_LENGTH
        @Size(max = 200) String reason) {

    /**
     * Class-level: the end must come after the start. Reported on the field endsAt.
     */
    @Documented
    @Constraint(validatedBy = EndsAfterStart.Validator.class)
    @Target(TYPE)
    @Retention(RUNTIME)
    public @interface EndsAfterStart {

        String message() default "must be after startsAt";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        // created by Hibernate Validator through reflection, so public
        class Validator implements ConstraintValidator<EndsAfterStart, NewUnavailability> {

            @Override
            public boolean isValid(NewUnavailability request, ConstraintValidatorContext context) {
                // missing times are left to NotNull
                if (request == null || request.startsAt() == null || request.endsAt() == null) {
                    return true;
                }
                if (request.endsAt().isAfter(request.startsAt())) {
                    return true;
                }
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("endsAt")
                        .addConstraintViolation();
                return false;
            }
        }
    }
}
