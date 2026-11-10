package com.posturestudio.booking.auth;

/**
 * How an access code reaches its destination (guide 24, part 2). SMS from step 8 piece 6 (guide 29), with the
 * migration V8 that widened login_code_channel_ck (D-042: only the values the code produces).
 */
enum LoginChannel {
    EMAIL,
    // to a verified phone (PhoneNumber, E.164) only
    SMS
}
