package com.posturestudio.booking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CodeCheck.AccountDeactivated;
import com.posturestudio.booking.auth.CodeCheck.Expired;
import com.posturestudio.booking.auth.CodeCheck.Valid;
import com.posturestudio.booking.auth.CodeCheck.Wrong;
import com.posturestudio.booking.auth.SmsRequests.PhoneAndCode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The session as a resource (guide 25): POST opens it with the code, GET says who is in, DELETE closes it.
 * Under /api/public because whoever signs in is not signed in yet; GET answers 401 when nobody is.
 * All three are protected by the CSRF token like any other request that changes something: also the
 * sign-in, against "login CSRF" (guide 25, 5.2).
 */
@RestController
@RequestMapping("/api/public/auth/session")
class SessionController {

    private final LoginCodeService codes;
    private final SessionAccounts sessions;
    private final CurrentUser currentUser;

    SessionController(LoginCodeService codes, SessionAccounts sessions, CurrentUser currentUser) {
        this.codes = codes;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    @PostMapping
    // @jakarta.validation.Valid written in full: Valid alone is CodeCheck.Valid in this class
    ResponseEntity<?> signIn(@jakarta.validation.Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        return answer(codes.verify(body.email(), body.code()), request);
    }

    // the same by SMS (guide 29, 8.1): the same answers, from the latest SMS code of the number
    @PostMapping("/sms")
    ResponseEntity<?> signInBySms(@jakarta.validation.Valid @RequestBody PhoneAndCode body, HttpServletRequest request) {
        return answer(codes.verifySms(body.phone(), body.code()), request);
    }

    // A switch over the sealed CodeCheck, with record patterns and no default: if a case is added to
    // CodeCheck, this stops compiling (guide 24, 4.2). newAccount is not needed here (the profile says what
    // is missing); from Java 22 it could be written as _
    private ResponseEntity<?> answer(CodeCheck check, HttpServletRequest request) {
        return switch (check) {
            case Valid(long userId, Role role, boolean newAccount) -> {
                sessions.signIn(request, userId);
                yield ResponseEntity.ok(new SessionView(role));
            }
            case Wrong() -> AuthErrorCode.CODE_WRONG.problem(HttpStatus.UNAUTHORIZED, "Wrong code");
            case Expired() -> AuthErrorCode.CODE_EXPIRED.problem(HttpStatus.UNAUTHORIZED,
                    "No valid code: ask for a new one");
            case AccountDeactivated() -> AuthErrorCode.ACCOUNT_DEACTIVATED.problem(HttpStatus.FORBIDDEN,
                    "The account is deactivated");
        };
    }

    @GetMapping
    SessionView current() {
        return new SessionView(currentUser.role());
    }

    @DeleteMapping
    ResponseEntity<Void> signOut(HttpServletRequest request) {
        sessions.signOut(request);
        return ResponseEntity.noContent().build();
    }
}
