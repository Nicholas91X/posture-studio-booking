package com.posturestudio.booking.auth;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A number PhoneNumber accepts (guide 29, part 2), checked at the edge of the API: a wrong one is a 400 on
 * its field, and tells nothing about who has an account. It delegates to PhoneNumber, as ValidEmail to Email.
 * null is valid: NotBlank stops it.
 */
@Documented
@Constraint(validatedBy = ValidPhone.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface ValidPhone {

    String message() default "must be an Italian mobile number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPhone, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                new PhoneNumber(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
