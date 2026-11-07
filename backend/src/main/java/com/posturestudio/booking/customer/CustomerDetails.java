package com.posturestudio.booking.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.posturestudio.booking.auth.ValidEmail;

/**
 * Who the booking is for, as the practitioner types it in the panel (D-024). The customer is found by
 * email, or created with these names; an existing customer keeps their own names (guide 22, 3.2).
 */
public record CustomerDetails(
        @NotBlank @ValidEmail String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName) {
}
