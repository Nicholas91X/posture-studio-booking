package com.posturestudio.booking.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.SmsRequests.PhoneOnly;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Asking for an access code (guide 24). Under /api/public: whoever asks is not signed in yet (D-029).
 * The check of the code, which opens the session, is SessionController.
 */
@RestController
@RequestMapping("/api/public/auth")
class LoginCodeController {

    private final LoginCodeService codes;

    LoginCodeController(LoginCodeService codes) {
        this.codes = codes;
    }

    // 202 Accepted, no body, the same for every email (guide 24, 5.1): "taken in charge", which is the truth
    @ApiResponse(responseCode = "202", description = "Taken in charge, the same for every email")
    @PostMapping("/login-code")
    ResponseEntity<Void> requestCode(@Valid @RequestBody LoginCodeRequest request, HttpServletRequest http) {
        codes.request(request.email(), ClientAddress.of(http));
        return ResponseEntity.accepted().build();
    }

    // The same by SMS (guide 29, part 4): 202 for every number, known or not. 503 at once when no SMS
    // provider is configured: the same for every number, so it tells nothing
    @ApiResponse(responseCode = "202", description = "Taken in charge, the same for every number")
    @PostMapping("/login-code/sms")
    ResponseEntity<?> requestSmsCode(@Valid @RequestBody PhoneOnly request, HttpServletRequest http) {
        if (!codes.smsAvailable()) {
            return AuthErrorCode.SMS_UNAVAILABLE.problem(HttpStatus.SERVICE_UNAVAILABLE, "SMS are not available");
        }
        codes.requestSms(request.phone(), ClientAddress.of(http));
        return ResponseEntity.accepted().build();
    }
}
