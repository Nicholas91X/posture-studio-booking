package com.posturestudio.booking.auth;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.SmsRequests.PhoneAndCode;
import com.posturestudio.booking.auth.SmsRequests.PhoneOnly;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * The phone of the signed-in account, of any role (guide 29, part 3). Under /api/account: the area of
 * whoever is signed in (guide 29, 8.2).
 *
 * A wrong code here is a 409, not the 401 of the sign-in: the person is signed in, and a 401 would make the
 * frontend believe the session had ended (guide 29, 8.3).
 */
@RestController
@RequestMapping("/api/account/phone")
class AccountPhoneController {

    private final LoginCodeService codes;
    private final CurrentUser currentUser;

    AccountPhoneController(LoginCodeService codes, CurrentUser currentUser) {
        this.codes = codes;
        this.currentUser = currentUser;
    }

    @GetMapping
    AccountPhoneView phone() {
        return codes.phoneOf(currentUser.id());
    }

    // a code to the number to add; 202, the SMS leaves in another thread
    @PostMapping("/code")
    ResponseEntity<?> requestCode(@Valid @RequestBody PhoneOnly body, HttpServletRequest http) {
        if (!codes.smsAvailable()) {
            return AuthErrorCode.SMS_UNAVAILABLE.problem(HttpStatus.SERVICE_UNAVAILABLE, "SMS are not available");
        }
        codes.requestPhoneVerification(currentUser.id(), body.phone(), ClientAddress.of(http));
        return ResponseEntity.accepted().build();
    }

    @PostMapping
    ResponseEntity<?> confirm(@Valid @RequestBody PhoneAndCode body) {
        return switch (codes.confirmPhone(currentUser.id(), body.phone(), body.code())) {
            case PhoneCheck.Verified(String phone, Instant verifiedAt) ->
                    ResponseEntity.ok(new AccountPhoneView(phone, verifiedAt));
            case PhoneCheck.Wrong() -> AuthErrorCode.CODE_WRONG.problem(HttpStatus.CONFLICT, "Wrong code");
            case PhoneCheck.Expired() -> AuthErrorCode.CODE_EXPIRED.problem(HttpStatus.CONFLICT,
                    "No valid code: ask for a new one");
        };
    }

    // no more SMS sign-in for this account; the email stays. 204 also when there was no number
    @DeleteMapping
    ResponseEntity<Void> remove() {
        codes.removePhone(currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
