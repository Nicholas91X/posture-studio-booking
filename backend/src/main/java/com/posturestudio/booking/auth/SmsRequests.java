package com.posturestudio.booking.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The bodies of the SMS APIs (guide 29, part 8). A number that is not an Italian mobile is a 400 on its
 * field: it is about the format, and tells nothing about who has an account. A code that is not six digits
 * is a 400 and uses no attempt (guide 25, part 10).
 */
final class SmsRequests {

    private SmsRequests() {
    }

    // POST /api/public/auth/login-code/sms and POST /api/account/phone/code
    record PhoneOnly(@NotBlank @ValidPhone String phone) {
    }

    // POST /api/public/auth/session/sms and POST /api/account/phone
    record PhoneAndCode(@NotBlank @ValidPhone String phone, @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }
}
