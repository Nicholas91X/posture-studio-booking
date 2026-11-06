package com.posturestudio.booking.shared;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The value must be one of the given ones, e.g. {@code @OneOf({30, 60})}.
 * Bean Validation has no built-in constraint for "one of these numbers".
 * Null is valid: it is the job of NotNull, so the two constraints stay independent.
 * The error reaches the client on its field, with the constraint "OneOf".
 */
@Documented
@Constraint(validatedBy = OneOfValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface OneOf {

    int[] value();

    String message() default "must be one of {value}";

    // the three attributes every Bean Validation constraint must declare
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
