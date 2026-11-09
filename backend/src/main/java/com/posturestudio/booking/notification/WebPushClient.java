package com.posturestudio.booking.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One POST to one push service (RFC 8030; guide 28, parts 8 and 9), with the HttpClient of the JDK.
 * It runs in the virtual thread of the notification: waiting for the network costs nothing there.
 *
 * The endpoint is never logged in full: it is the personal address of a device (guide 28, 6.5).
 */
@Component
class WebPushClient implements AutoCloseable {

    enum Outcome {
        // 2xx: taken in charge by the push service (not yet shown on the phone, guide 28, 8.3)
        ACCEPTED,
        // 404 or 410: the subscription no longer exists, the row is deleted
        GONE,
        // anything else: this device is not reached this time
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(WebPushClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final Vapid vapid;
    // thread-safe, made once; closed with the application (AutoCloseable: Spring calls close)
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    WebPushClient(Vapid vapid) {
        this.vapid = vapid;
    }

    Outcome send(String endpoint, byte[] encryptedBody, Duration timeToLive) {
        URI uri = URI.create(endpoint);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", vapid.authorization(uri))
                .header("TTL", Long.toString(timeToLive.toSeconds()))
                // deliver at once, even to a phone saving battery: the request has a deadline
                .header("Urgency", "high")
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
                .build();
        int status;
        try {
            status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            log.warn("Push to {} not delivered: {}", uri.getHost(), e.toString());
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            // the thread is being stopped (shutdown): keep the signal for whoever stops it
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
        return outcomeOf(status, uri.getHost());
    }

    private static Outcome outcomeOf(int status, String host) {
        if (status >= 200 && status < 300) {
            return Outcome.ACCEPTED;
        }
        if (status == 404 || status == 410) {
            return Outcome.GONE;
        }
        // 400, 403, 413: our request is wrong (a bug, or the VAPID keys changed); 429 and 5xx: their side
        if (status == 429 || status >= 500) {
            log.warn("Push to {} refused with {}: the push service is busy or down", host, status);
        } else {
            log.error("Push to {} refused with {}: the request is wrong", host, status);
        }
        return Outcome.FAILED;
    }

    @Override
    public void close() {
        http.close();
    }
}
