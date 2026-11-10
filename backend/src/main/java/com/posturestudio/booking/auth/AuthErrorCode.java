package com.posturestudio.booking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

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
    ACCOUNT_DEACTIVATED,
    // 503: no SMS provider is configured (guide 29, 6.2); the frontend hides the SMS option and offers the email
    SMS_UNAVAILABLE;

    // the same ProblemDetail shape as ApiExceptionHandler, with this code (guide 25, part 10)
    ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", name());
        return ResponseEntity.status(status).body(problem);
    }
}
