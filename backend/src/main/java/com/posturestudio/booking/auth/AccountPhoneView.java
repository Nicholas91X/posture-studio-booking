package com.posturestudio.booking.auth;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The phone of the signed-in account (guide 29, part 8): GET /api/account/phone. Both null when there is
 * none; when there is one, it is verified (V8: there are no unverified numbers).
 */
record AccountPhoneView(@Schema(nullable = true) String phone, @Schema(nullable = true) Instant verifiedAt) {

    static final AccountPhoneView NONE = new AccountPhoneView(null, null);

    static AccountPhoneView from(AppUser user) {
        return new AccountPhoneView(user.getPhone(), user.getPhoneVerifiedAt());
    }
}
