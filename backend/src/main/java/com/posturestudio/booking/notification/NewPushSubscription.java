package com.posturestudio.booking.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * A browser's subscription, in the shape of PushSubscription.toJSON() (guide 28, 1.2), so the frontend
 * sends it as the browser gives it. Other fields of that JSON (expirationTime) are ignored.
 * Errors are reported on their field: endpoint, keys.p256dh, keys.auth.
 */
record NewPushSubscription(
        @NotBlank @PushEndpoint String endpoint,
        @NotNull @Valid Keys keys) {

    record Keys(
            @NotBlank @BrowserPublicKey String p256dh,
            // 16 bytes in base64url: 22 characters, maybe with the padding "=="
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{22}(==)?") String auth) {
    }
}
