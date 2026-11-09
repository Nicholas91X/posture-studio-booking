package com.posturestudio.booking.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// The body of POST /api/public/auth/session. A code that is not six digits is a 400 and uses no attempt:
// it could not be right (guide 25, part 10)
record SignInRequest(@NotBlank @ValidEmail String email, @NotBlank @Pattern(regexp = "\\d{6}") String code) {
}
