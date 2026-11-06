package com.posturestudio.booking.schedule;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.LocalTime;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * The time is on the hour or on the half hour, with no seconds: 09:00 and 09:30, not 09:15 (D-033).
 * Null is valid: it is the job of NotNull.
 */
@Documented
@Constraint(validatedBy = OnTheHalfHour.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface OnTheHalfHour {

    String message() default "must be on the hour or on the half hour";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // created by Hibernate Validator through reflection, so public
    class Validator implements ConstraintValidator<OnTheHalfHour, LocalTime> {

        @Override
        public boolean isValid(LocalTime value, ConstraintValidatorContext context) {
            return value == null || WorkingBand.isOnTheHalfHour(value);
        }
    }
}
