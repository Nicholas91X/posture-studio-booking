package com.posturestudio.booking.credit;

import java.time.LocalDate;

import com.posturestudio.booking.customer.CustomerDetails;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A gift of sessions (D-067, guide 40, part 3): for a customer found or created by email (D-066, D-048), on an own
 * service on sale. validUntil: the last valid day; missing, that of a single session (single_credit_validity_days).
 * note: why, always (D-067). Wrappers instead of primitives: see ServiceOfferDetails
 */
record NewCreditGrant(
        @NotNull @Valid CustomerDetails customer,
        @NotNull Long serviceId,
        @NotNull @Positive Integer credits,
        LocalDate validUntil,
        @NotBlank @Size(max = 500) String note) {
}
