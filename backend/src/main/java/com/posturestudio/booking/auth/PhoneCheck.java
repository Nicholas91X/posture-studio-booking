package com.posturestudio.booking.auth;

import java.time.Instant;

/**
 * The outcome of confirming a phone with its code (guide 29, 8.3). A result and not an exception, as
 * CodeCheck: an exception would roll back the attempt just counted (guide 24, 4.1). sealed: a switch over
 * it lists every case with no default.
 */
sealed interface PhoneCheck {

    // the number is now this account's, verified at this instant
    record Verified(String phone, Instant verifiedAt) implements PhoneCheck {
    }

    // a wrong code, with attempts left
    record Wrong() implements PhoneCheck {
    }

    // no code to check: never requested by this account for this number, expired, used, or 5 wrong attempts
    record Expired() implements PhoneCheck {
    }
}
