package com.posturestudio.booking.auth;

/**
 * What a code is for (guide 29, 3.2). Same rules, same table, but never one for the other: a verification
 * code does not sign in, a sign-in code does not verify a number. login_code_purpose_ck (V8) lists the same.
 * Public: the SMS sender of notification writes a different first line for each.
 */
public enum CodePurpose {
    SIGN_IN,
    // a signed-in account proves it has this phone; only by SMS (login_code_verify_by_sms_ck)
    VERIFY_PHONE
}
