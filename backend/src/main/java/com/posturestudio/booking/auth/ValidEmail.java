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
 * An email the Email record accepts (D-020), checked at the edge of the API so that a wrong one is a 400
 * on its field and not a 500 from the record. It delegates to Email: one rule in one place (guide 22, 3.4).
 * jakarta.validation.constraints.Email would accept addresses that Email refuses, such as mario@localhost.
 * null is valid: NotBlank stops it.
 */
@Documented
@Constraint(validatedBy = ValidEmail.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface ValidEmail {

    String message() default "must be a valid email address";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // created by Hibernate Validator through reflection, so public
    class Validator implements ConstraintValidator<ValidEmail, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                new Email(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
