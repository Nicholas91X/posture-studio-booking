package com.posturestudio.booking.credit;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// An extension of a lot (D-067, guide 40, part 4): the new last valid day, forward only, and why
record NewCreditExtension(
        @NotNull LocalDate validUntil,
        @NotBlank @Size(max = 500) String note) {
}
