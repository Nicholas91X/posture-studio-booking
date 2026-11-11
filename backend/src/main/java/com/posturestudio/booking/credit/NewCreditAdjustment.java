package com.posturestudio.booking.credit;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// A correction of a lot (D-067, guide 40, part 5): +N or -N, never zero, never below zero, and why
record NewCreditAdjustment(
        @NotNull Integer amount,
        @NotBlank @Size(max = 500) String note) {

    // A rule on one field that no standard annotation says: a method named like a getter, so that Bean
    // Validation finds it and reports it as "amountNotZero". null is left to @NotNull
    @AssertTrue(message = "must not be zero")
    boolean isAmountNotZero() {
        return amount == null || amount != 0;
    }
}
