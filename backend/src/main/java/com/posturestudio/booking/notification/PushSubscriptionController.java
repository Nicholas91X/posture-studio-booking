package com.posturestudio.booking.notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * The devices of the signed-in practitioner (guide 28, part 6). Under /api/panel: practitioners only (D-051).
 * Used by the panel from step 13: it asks the public key, subscribes the browser with it, and sends the
 * subscription here at every visit; it unsubscribes when the practitioner turns notifications off or signs out.
 */
@RestController
@RequestMapping("/api/panel/push-subscriptions")
class PushSubscriptionController {

    record VapidKey(String publicKey) {
    }

    record Unsubscribe(@NotBlank String endpoint) {
    }

    private final PushSubscriptionService service;
    private final CurrentPractitioner currentPractitioner;

    PushSubscriptionController(PushSubscriptionService service, CurrentPractitioner currentPractitioner) {
        this.service = service;
        this.currentPractitioner = currentPractitioner;
    }

    // the applicationServerKey of the browser's subscribe(): public by nature (guide 28, 5.1)
    @GetMapping("/vapid-key")
    VapidKey vapidKey() {
        return new VapidKey(service.vapidPublicKey());
    }

    // 204: nothing to give back; the same call again changes nothing
    @PostMapping
    ResponseEntity<Void> register(@Valid @RequestBody NewPushSubscription subscription) {
        service.register(currentPractitioner.id(), subscription);
        return ResponseEntity.noContent().build();
    }

    // 204 also when there was nothing to remove, or when the endpoint is another practitioner's
    @PostMapping("/unsubscribe")
    ResponseEntity<Void> unsubscribe(@Valid @RequestBody Unsubscribe request) {
        service.unsubscribe(currentPractitioner.id(), request.endpoint());
        return ResponseEntity.noContent().build();
    }
}
