package com.posturestudio.booking.notification;

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
 * The p256dh of a subscription: base64url of a P-256 public key that P256 accepts, a point of the curve
 * (guide 28, 3.3). It delegates to P256, as ValidEmail delegates to Email: one rule in one place.
 * A wrong key is a 400 when registering, not a push that can never be encrypted. null is valid: NotBlank stops it.
 */
@Documented
@Constraint(validatedBy = BrowserPublicKey.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@interface BrowserPublicKey {

    String message() default "must be the base64url of a P-256 public key";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BrowserPublicKey, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                P256.publicKey(P256.fromBase64url(value));
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
