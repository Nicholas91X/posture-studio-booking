package com.posturestudio.booking.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The devices of the practitioners (guide 28, part 6): registered, removed, and the public key they
 * subscribe with. Sending is PushSender's.
 */
@Service
class PushSubscriptionService {

    // largely enough for one person; a buggy frontend registering a new subscription at every visit
    // cannot grow the table, nor the requests of every push (guide 28, 6.3)
    static final int MAX_DEVICES = 10;

    private final PushSubscriptionRepository subscriptions;
    private final Vapid vapid;
    private final PushSender sender;
    private final PushMessages messages;

    PushSubscriptionService(PushSubscriptionRepository subscriptions, Vapid vapid, PushSender sender,
                            PushMessages messages) {
        this.subscriptions = subscriptions;
        this.vapid = vapid;
        this.sender = sender;
        this.messages = messages;
    }

    String vapidPublicKey() {
        return vapid.publicKey();
    }

    // idempotent: the same endpoint updates its row, also when it now belongs to this practitioner
    @Transactional
    void register(long practitionerId, NewPushSubscription subscription) {
        subscriptions.upsert(practitionerId, subscription.endpoint(), subscription.keys().p256dh(),
                subscription.keys().auth());
        subscriptions.keepNewest(practitionerId, MAX_DEVICES);
    }

    // idempotent, and only an own device
    @Transactional
    void unsubscribe(long practitionerId, String endpoint) {
        subscriptions.deleteOwn(practitionerId, endpoint);
    }

    // A test push to every device of this practitioner (D-088), sent now, in the request's thread: the panel
    // waits for the answer to say whether it worked. No transaction: PushSender deletes a dead device by itself.
    // true if at least one device took it; false with no devices, or when none took it
    boolean sendTest(long practitionerId) {
        return sender.sendToPractitioner(practitionerId, messages.test());
    }
}
