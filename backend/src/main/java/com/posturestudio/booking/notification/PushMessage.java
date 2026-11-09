package com.posturestudio.booking.notification;

import java.time.Duration;

/**
 * A push to send (guide 28, part 10): what the notification shows, and how long the push service keeps
 * it for a phone that cannot be reached (TTL, guide 28, 8.3).
 */
record PushMessage(PushPayload payload, Duration timeToLive) {

    /**
     * The JSON the service worker of the panel reads (step 13): a contract, so a record, turned into JSON
     * by Jackson and never written by hand. tag: notifications with the same tag replace each other.
     */
    record PushPayload(String title, String body, String url, String tag) {
    }
}
