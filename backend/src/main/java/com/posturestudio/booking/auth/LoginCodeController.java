package com.posturestudio.booking.auth;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Asking for an access code (guide 24). Under /api/public: whoever asks is not signed in yet (D-029).
 * The check of the code, which opens the session, is step 8 piece 2.
 */
@RestController
@RequestMapping("/api/public/auth")
class LoginCodeController {

    private final LoginCodeService codes;

    LoginCodeController(LoginCodeService codes) {
        this.codes = codes;
    }

    // 202 Accepted, no body, the same for every email (guide 24, 5.1): "taken in charge", which is the truth
    @PostMapping("/login-code")
    ResponseEntity<Void> requestCode(@Valid @RequestBody LoginCodeRequest request, HttpServletRequest http) {
        codes.request(request.email(), clientAddress(http));
        return ResponseEntity.accepted().build();
    }

    // The address of the client. Behind Caddy, Tomcat has already replaced it with the one in
    // X-Forwarded-For, trusting only a proxy on a private network (server.forward-headers-strategy=native).
    // The header is never read here: anyone can write it (guide 24, 6.4).
    // getRemoteAddr is always an IP literal, so getByName does not ask any DNS
    private static InetAddress clientAddress(HttpServletRequest http) {
        try {
            return InetAddress.getByName(http.getRemoteAddr());
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Not an IP address: " + http.getRemoteAddr(), e);
        }
    }
}
