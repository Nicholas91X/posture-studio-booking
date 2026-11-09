package com.posturestudio.booking.auth;

import jakarta.validation.constraints.NotBlank;

// The body of POST /api/public/auth/login-code. A malformed email is a 400: it is about the format,
// and tells nothing about who has an account (guide 24, part 8)
record LoginCodeRequest(@NotBlank @ValidEmail String email) {
}
