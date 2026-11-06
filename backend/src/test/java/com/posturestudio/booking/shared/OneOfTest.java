package com.posturestudio.booking.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Bean Validation without Spring: the same engine (Hibernate Validator) the controllers use
public class OneOfTest {

    // a record of its own, so the test does not depend on PractitionerSettings
    record ShirtOrder(@OneOf({38, 40, 42}) Integer size) {
    }

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void allowedValuesPass() {
        assertThat(validator.validate(new ShirtOrder(38))).isEmpty();
        assertThat(validator.validate(new ShirtOrder(42))).isEmpty();
    }

    @Test
    void otherValueFailsOnItsField() {
        Set<ConstraintViolation<ShirtOrder>> violations = validator.validate(new ShirtOrder(39));

        assertThat(violations).hasSize(1);
        ConstraintViolation<ShirtOrder> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("size");
        assertThat(violation.getMessage()).isEqualTo("must be one of [38, 40, 42]");
    }

    @Test
    void nullPassesBecauseItIsTheJobOfNotNull() {
        assertThat(validator.validate(new ShirtOrder(null))).isEmpty();
    }
}
