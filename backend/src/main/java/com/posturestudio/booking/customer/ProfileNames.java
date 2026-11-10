package com.posturestudio.booking.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The body of PUT /api/customer/profile: the same limits as the names typed in the panel (CustomerDetails)
public record ProfileNames(@NotBlank @Size(max = 100) String firstName, @NotBlank @Size(max = 100) String lastName) {
}
