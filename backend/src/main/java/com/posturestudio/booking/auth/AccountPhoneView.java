package com.posturestudio.booking.auth;

import java.time.Instant;

/**
 * The phone of the signed-in account (guide 29, part 8): GET /api/account/phone. Both null when there is
 * none; when there is one, it is verified (V8: there are no unverified numbers).
 */
record AccountPhoneView(String phone, Instant verifiedAt) {

    static final AccountPhoneView NONE = new AccountPhoneView(null, null);

    static AccountPhoneView from(AppUser user) {
        return new AccountPhoneView(user.getPhone(), user.getPhoneVerifiedAt());
    }
}
