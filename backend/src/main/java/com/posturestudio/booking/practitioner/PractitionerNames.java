package com.posturestudio.booking.practitioner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The body of PUT /api/admin/practitioners/{id}: only the names, never the email (guide 26, part 8)
public record PractitionerNames(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName) {
}
