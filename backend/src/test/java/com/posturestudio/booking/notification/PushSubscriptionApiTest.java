package com.posturestudio.booking.notification;

import static com.posturestudio.booking.notification.PushEncryptionTest.AUTH;
import static com.posturestudio.booking.notification.PushEncryptionTest.BROWSER_PUBLIC;
import static com.posturestudio.booking.notification.PushEncryptionTest.SERVER_PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The devices of the practitioners (guide 28, parts 6 and 7), through the API with real sessions.
// @Transactional: everything is rolled back at the end
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PushSubscriptionApiTest {

    private static final String FCM = "https://fcm.googleapis.com/fcm/send/";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;

    @BeforeEach
    void insertPractitioners() {
        anna = insertPractitioner("push-anna@example.com");
        marco = insertPractitioner("push-marco@example.com");
    }

    // what the browser needs to subscribe: the public key of the server, the pair of the tests
    @Test
    void thePanelGetsThePublicKey() {
        assertThat(mvc.get().uri("/api/panel/push-subscriptions/vapid-key").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("{\"publicKey\": \"" + SERVER_PUBLIC + "\"}");
    }

    @Test
    void aSubscriptionIsStoredAsTheBrowserGivesIt() {
        // the shape of PushSubscription.toJSON(), expirationTime included and ignored
        assertThat(register(anna, FCM + "device-1", AUTH)).hasStatus(HttpStatus.NO_CONTENT);

        Map<String, Object> row = jdbc.queryForMap("select practitioner_id, endpoint, p256dh, auth from push_subscription");
        assertThat(row).containsEntry("practitioner_id", anna)
                .containsEntry("endpoint", FCM + "device-1")
                .containsEntry("p256dh", BROWSER_PUBLIC)
                .containsEntry("auth", AUTH);
    }

    // the frontend sends it at every visit, and the browser may renew the keys: one row, the last keys
    @Test
    void theSameEndpointAgainUpdatesItsRow() {
        register(anna, FCM + "device-1", AUTH);

        assertThat(register(anna, FCM + "device-1", "AAAAAAAAAAAAAAAAAAAAAA")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForList("select auth from push_subscription", String.class))
                .containsExactly("AAAAAAAAAAAAAAAAAAAAAA");
    }

    // a tablet of the studio, now used by Marco: the device is his
    @Test
    void theSameEndpointFromAnotherPractitionerMovesToThem() {
        register(anna, FCM + "tablet", AUTH);

        register(marco, FCM + "tablet", AUTH);

        assertThat(jdbc.queryForList("select practitioner_id from push_subscription", Long.class)).containsExactly(marco);
    }

    // past the limit the oldest goes. Inside one test transaction now() is the same for every row:
    // the id breaks the tie, as in the query
    @Test
    void theEleventhDeviceRemovesTheOldest() {
        for (int device = 0; device <= PushSubscriptionService.MAX_DEVICES; device++) {
            register(anna, FCM + "device-" + device, AUTH);
        }

        assertThat(jdbc.queryForList("select endpoint from push_subscription where practitioner_id = ?", String.class, anna))
                .hasSize(PushSubscriptionService.MAX_DEVICES)
                .doesNotContain(FCM + "device-0")
                .contains(FCM + "device-" + PushSubscriptionService.MAX_DEVICES);
    }

    // SSRF (guide 28, part 7): the server never writes to an address outside the push services
    @Test
    void anAddressOutsideThePushServicesIs400OnItsField() {
        MvcTestResult result = register(anna, "http://localhost:8080/api/admin/practitioners", AUTH);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("endpoint");
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Long.class)).isZero();
    }

    @Test
    void wrongKeysAre400OnTheirFields() {
        String offTheCurve = BROWSER_PUBLIC.substring(0, BROWSER_PUBLIC.length() - 2) + "AA";

        MvcTestResult result = mvc.post().uri("/api/panel/push-subscriptions").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON)
                .content(subscription(FCM + "device-1", offTheCurve, "short"))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("keys.p256dh", "keys.auth");
    }

    // only an own device: Marco cannot turn off Anna's notifications
    @Test
    void unsubscribingRemovesOnlyAnOwnDevice() {
        register(anna, FCM + "device-1", AUTH);

        assertThat(unsubscribe(sessions.practitioner(marco), FCM + "device-1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Long.class)).isOne();

        assertThat(unsubscribe(sessions.practitioner(anna), FCM + "device-1")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Long.class)).isZero();

        // again: nothing to remove, the same answer
        assertThat(unsubscribe(sessions.practitioner(anna), FCM + "device-1")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void onlyPractitionersRegisterDevices() {
        Long customer = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', 'push-cliente@example.com') returning id", Long.class);

        assertThat(mvc.get().uri("/api/panel/push-subscriptions/vapid-key")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/panel/push-subscriptions").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(subscription(FCM + "x", BROWSER_PUBLIC, AUTH)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/panel/push-subscriptions").with(sessions.user(customer))
                .contentType(MediaType.APPLICATION_JSON).content(subscription(FCM + "x", BROWSER_PUBLIC, AUTH)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Long.class)).isZero();
    }

    // ***** helpers *****

    private MvcTestResult register(long practitioner, String endpoint, String auth) {
        return mvc.post().uri("/api/panel/push-subscriptions").with(sessions.practitioner(practitioner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(subscription(endpoint, BROWSER_PUBLIC, auth))
                .exchange();
    }

    private MvcTestResult unsubscribe(RequestPostProcessor session, String endpoint) {
        return mvc.post().uri("/api/panel/push-subscriptions/unsubscribe").with(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\": \"" + endpoint + "\"}")
                .exchange();
    }

    private static String subscription(String endpoint, String p256dh, String auth) {
        return """
                {"endpoint": "%s", "expirationTime": null, "keys": {"p256dh": "%s", "auth": "%s"}}
                """.formatted(endpoint, p256dh, auth);
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
