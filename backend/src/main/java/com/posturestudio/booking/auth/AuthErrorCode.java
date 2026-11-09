package com.posturestudio.booking.auth;

/**
 * Readable codes of the sign-in, sent to the frontend that translates them into Italian (guide 25, part 10).
 * Kept apart from ErrorCode, which lists the 409 of the business rules (D-027): these have other statuses.
 * Part of the contract of the API: once published, never renamed.
 */
enum AuthErrorCode {

    // 401: a wrong code, with attempts left
    CODE_WRONG,

    // 401: no code to check (expired, used, 5 wrong attempts, never requested): ask for a new one
    CODE_EXPIRED,

    // 403: the right code, but the account is deactivated (D-050); only the owner of the email sees this
    ACCOUNT_DEACTIVATED
}
