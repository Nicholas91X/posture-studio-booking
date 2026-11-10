package com.posturestudio.booking.practitioner;

import com.posturestudio.booking.auth.ValidEmail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The body of POST /api/admin/practitioners (guide 26, part 4). The email is the one the practitioner will
// sign in with; the names are what customers see, with the same limits as the names of a customer
public record NewPractitioner(
        @NotBlank @ValidEmail String email,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName) {
}
