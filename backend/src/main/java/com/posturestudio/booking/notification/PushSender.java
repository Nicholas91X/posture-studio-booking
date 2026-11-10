package com.posturestudio.booking.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * A push to every device of a practitioner (guide 28, parts 8 and 9). Called by BookingNotifications in
 * its virtual thread, after the commit: no transaction is bound to the thread, so deleting a subscription
 * that no longer exists is a transaction of its own, with no need for REQUIRES_NEW (guide 27, 3.2).
 */
@Component
class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    private final PushSubscriptionRepository subscriptions;
    private final WebPushClient client;
    private final JsonMapper json;

    PushSender(PushSubscriptionRepository subscriptions, WebPushClient client, JsonMapper json) {
        this.subscriptions = subscriptions;
        this.client = client;
        this.json = json;
    }

    /**
     * @return true if at least one device took the push: then the email to the practitioner is not needed
     * (D-032, guide 28, 8.2). false with no devices, or when none took it
     */
    boolean sendToPractitioner(long practitionerId, PushMessage message) {
        byte[] plaintext = json.writeValueAsBytes(message.payload());
        int accepted = 0;
        for (PushSubscription device : subscriptions.findByPractitionerIdOrderByRegisteredAtDesc(practitionerId)) {
            // each device on its own, as each email: a broken one does not stop the others
            try {
                byte[] body = PushEncryption.encrypt(P256.fromBase64url(device.getP256dh()),
                        P256.fromBase64url(device.getAuth()), plaintext);
                switch (client.send(device.getEndpoint(), body, message.timeToLive())) {
                    case ACCEPTED -> accepted++;
                    case GONE -> {
                        // the browser dropped it (permission removed, app uninstalled): never try it again
                        subscriptions.deleteById(device.getId());
                        log.info("Push subscription {} of practitioner {} no longer exists: deleted", device.getId(), practitionerId);
                    }
                    case FAILED -> {
                        // logged by the client; the device stays, and counts as not reached this time
                    }
                }
            } catch (RuntimeException e) {
                log.error("Push to subscription {} of practitioner {} not sent", device.getId(), practitionerId, e);
            }
        }
        return accepted > 0;
    }
}
