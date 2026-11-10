package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Where the server accepts to write (guide 28, part 7): the known push services over https, nothing else.
// Every refused address is one a stolen panel account could otherwise make the server call (SSRF)
class PushEndpointTest {

    private final PushEndpoint.Validator validator = new PushEndpoint.Validator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/dXJ1bjpleGFtcGxl",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAAABl",
            "https://web.push.apple.com/QGuQyavXutnMYv0",
            "https://wns2-par02p.notify.windows.com/w/?token=BQYAAAB",
            "https://fcm.googleapis.com:443/fcm/send/abc"})
    void thePushServicesAreAccepted(String endpoint) {
        assertThat(validator.isValid(endpoint, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // not https
            "http://fcm.googleapis.com/fcm/send/abc",
            // the server itself, the cloud metadata, the Docker network
            "https://localhost/push",
            "https://169.254.169.254/latest/meta-data",
            "https://postgres:5432/",
            // looks like a push service, is not one
            "https://fcm.googleapis.com.example.com/fcm/send/abc",
            "https://evilfcm.googleapis.com/fcm/send/abc",
            "https://example.com/?https://fcm.googleapis.com",
            // another port, credentials in front of the host
            "https://fcm.googleapis.com:8443/fcm/send/abc",
            "https://user:secret@fcm.googleapis.com/fcm/send/abc",
            "not a url"})
    void everythingElseIsRefused(String endpoint) {
        assertThat(validator.isValid(endpoint, null)).isFalse();
    }
}
