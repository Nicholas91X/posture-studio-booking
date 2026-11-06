package com.posturestudio.booking.shared;

import java.util.Arrays;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Created by Hibernate Validator through reflection, so public: initialize once with the annotation,
// then isValid for each value
public class OneOfValidator implements ConstraintValidator<OneOf, Integer> {

    private int[] allowed;

    @Override
    public void initialize(OneOf annotation) {
        this.allowed = annotation.value().clone();
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return Arrays.stream(allowed).anyMatch(candidate -> candidate == value);
    }
}
