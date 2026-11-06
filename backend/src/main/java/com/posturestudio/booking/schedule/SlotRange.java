package com.posturestudio.booking.schedule;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;

/**
 * The local days a customer asks free slots for: ?from=2026-11-09&to=2026-11-15.
 * Bound from the query string (no @RequestBody: an implicit @ModelAttribute, built through the
 * constructor), so a wrong range is the same 400 as a wrong JSON body.
 * No limit on the distance: the calculation cuts it to the booking horizon (guide 16, 5.3).
 */
@SlotRange.FromNotAfterTo
public record SlotRange(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    /**
     * Class-level: from is not after to. Reported on the field to.
     */
    @Documented
    @Constraint(validatedBy = FromNotAfterTo.Validator.class)
    @Target(TYPE)
    @Retention(RUNTIME)
    public @interface FromNotAfterTo {

        String message() default "must not be before from";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        // created by Hibernate Validator through reflection, so public
        class Validator implements ConstraintValidator<FromNotAfterTo, SlotRange> {

            @Override
            public boolean isValid(SlotRange range, ConstraintValidatorContext context) {
                // missing dates are left to NotNull
                if (range == null || range.from() == null || range.to() == null || !range.from().isAfter(range.to())) {
                    return true;
                }
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode("to")
                        .addConstraintViolation();
                return false;
            }
        }
    }
}
