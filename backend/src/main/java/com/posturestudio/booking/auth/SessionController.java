package com.posturestudio.booking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    // A switch over the sealed CodeCheck, with record patterns and no default: if a case is added to
    // CodeCheck, this stops compiling (guide 24, 4.2). newAccount is not needed here (the profile says what
    // is missing); from Java 22 it could be written as _
    @PostMapping
    // @jakarta.validation.Valid written in full: Valid alone is CodeCheck.Valid in this class
    ResponseEntity<?> signIn(@jakarta.validation.Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        return switch (codes.verify(body.email(), body.code())) {
            case Valid(long userId, Role role, boolean newAccount) -> {
                sessions.signIn(request, userId);
                yield ResponseEntity.ok(new SessionView(role));
            }
            case Wrong() -> problem(HttpStatus.UNAUTHORIZED, AuthErrorCode.CODE_WRONG, "Wrong code");
            case Expired() -> problem(HttpStatus.UNAUTHORIZED, AuthErrorCode.CODE_EXPIRED,
                    "No valid code: ask for a new one");
            case AccountDeactivated() -> problem(HttpStatus.FORBIDDEN, AuthErrorCode.ACCOUNT_DEACTIVATED,
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

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, AuthErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return ResponseEntity.status(status).body(problem);
    }
}
