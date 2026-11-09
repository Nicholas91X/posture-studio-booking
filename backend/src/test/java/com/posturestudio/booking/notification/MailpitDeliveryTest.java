package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.booking.NewPanelBooking;
import com.posturestudio.booking.customer.CustomerDetails;

// The only test that really sends (guide 27, part 9): the real JavaMailSender of Spring Boot, over SMTP, to
// Mailpit in a container (the image of infra/docker-compose.dev.yml), read back with Mailpit's HTTP API.
// What the recording sender cannot see: the configuration of Spring Mail, the headers, the encoding of
// the accented letters (UTF-8). Its own context, because of the mail properties
@SpringBootTest(properties = "booking.jobs.enabled=false")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Testcontainers
class MailpitDeliveryTest {

    private static final int SMTP = 1025;
    private static final int API = 8025;

    // started before the context and stopped after the class by the Testcontainers extension, which closes it
    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.33").withExposedPorts(SMTP, API);

    // where the container listens is known only once it runs: the properties are read from it
    @DynamicPropertySource
    static void sendToMailpit(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(SMTP));
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;

    @AfterEach
    void deleteWhatWasCommitted() {
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where user_id in (select id from app_user where email like 'mailpit-%')");
        jdbc.update("delete from app_user where email like 'mailpit-%'");
    }

    @Test
    void aConfirmationReachesMailpitAsWritten() throws Exception {
        Long annaUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'mailpit-anna@example.com') returning id", Long.class);
        anna = jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Anna', 'Rossi') returning id",
                Long.class, annaUser);
        long seduta = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 5000) returning id",
                Long.class, anna);
        Long schedule = jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, '2026-11-02') returning id",
                Long.class, anna);
        jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, 1, '09:00', '13:00')",
                schedule);

        // a new customer, created by the panel booking itself: their first email from the studio
        bookings.bookFromPanel(anna, new NewPanelBooking(seduta, OffsetDateTime.parse("2026-11-09T09:00+01:00"),
                new CustomerDetails("mailpit-maria@example.com", "Maria", "Bianchi")));

        await().atMost(Duration.ofSeconds(10))
                .until(() -> JsonPath.<Integer>read(get("/api/v1/messages"), "$.total") == 1);
        String id = JsonPath.read(get("/api/v1/messages"), "$.messages[0].ID");
        String message = get("/api/v1/message/" + id);

        assertThat(JsonPath.<String>read(message, "$.From.Address")).isEqualTo("no-reply@posture-studio.local");
        assertThat(JsonPath.<String>read(message, "$.To[0].Address")).isEqualTo("mailpit-maria@example.com");
        assertThat(JsonPath.<String>read(message, "$.Subject")).isEqualTo("Prenotazione confermata: lunedì 9 novembre, 9:00");
        assertThat(JsonPath.<String>read(message, "$.Text"))
                .contains("Gentile Maria,", "la tua prenotazione è confermata:",
                        "Seduta con Anna Rossi", "lunedì 9 novembre 2026, ore 9:00",
                        "Questa è un'email automatica");
    }

    private String get(String path) throws IOException, InterruptedException {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(API) + path);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
