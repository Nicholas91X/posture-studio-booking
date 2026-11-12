package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// The deactivation of a practitioner (D-077, guide 45): refused with sessions to come, the admin's way out of the
// urgency, the warning of the credits, and what the customer sees. Today is Friday 6 November 2026, 10:00 in Rome;
// the sessions are on Monday 9 November. In a transaction rolled back at the end; the race with a new booking needs
// real commits, in PractitionerDeactivationRaceTest
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class})
@Transactional
class PractitionerDeactivationApiTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    EntityManager entityManager;

    private BookingTestData data;
    private long admin;
    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaHerCustomerAndTheAdmin() {
        clock.reset();
        data = new BookingTestData(jdbc);
        admin = jdbc.queryForObject("insert into app_user (role, email) values ('ADMIN', 'admin@example.com') returning id",
                Long.class);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        cliente = data.customer("maria@example.com", "Maria", "Bianchi");
    }

    // ***** what stops it (guide 45, part 2) *****

    @Test
    void aConfirmedSessionToComeStopsTheDeactivation() {
        data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);

        MvcTestResult result = deactivate();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PRACTITIONER_HAS_FUTURE_BOOKINGS");
        assertThat(annaIsActive()).isTrue();
    }

    @Test
    void aRequestInWaitStopsIt() {
        data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_APPROVAL");

        assertThat(deactivate()).hasStatus(HttpStatus.CONFLICT);
    }

    // the customer is paying: wait, at most 32 minutes (D-061)
    @Test
    void aPaymentInProgressStopsIt() {
        data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_PAYMENT");

        assertThat(deactivate()).hasStatus(HttpStatus.CONFLICT);
    }

    // a hold already gone is lost: the job closes it within a minute
    @Test
    void anExpiredHoldDoesNotStopIt() {
        data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "PENDING_PAYMENT",
                "2026-11-06T09:30+01:00");

        assertThat(deactivate()).hasStatusOk();
    }

    // Monday 9:30: the session is taking place, the practitioner is doing it
    @Test
    void aSessionInProgressStopsIt() {
        data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);
        clock.advance(Duration.ofHours(71).plusMinutes(30));

        assertThat(deactivate()).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void sessionsOverOrCancelledDoNotStopIt() {
        data.confirmed(anna, seduta, cliente, "2026-11-05T09:00+01:00", "CARD", null);
        data.booking(anna, seduta, cliente, MONDAY_9, "2026-11-09T10:00+01:00", "REJECTED");

        MvcTestResult result = deactivate();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.practitioner.active").isEqualTo(false);
    }

    // ***** the urgency: the admin cancels for the practitioner (guide 45, part 5) *****

    @Test
    void theAdminSeesWhatStopsItCancelsItAndThenDeactivates() {
        long booking = data.confirmed(anna, seduta, cliente, MONDAY_9, "CARD", null);

        MvcTestResult list = mvc.get().uri("/api/admin/practitioners/" + anna + "/bookings").with(sessions.user(admin))
                .exchange();
        assertThat(list).hasStatusOk();
        assertThat(list).bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) booking);
        // what to cancel, not who
        assertThat(list).bodyJson().extractingPath("$[0]").asMap().doesNotContainKeys("customerId", "firstName");

        MvcTestResult cancelled = mvc.post().uri("/api/admin/practitioners/" + anna + "/bookings/" + booking + "/cancel")
                .with(sessions.user(admin)).exchange();
        assertThat(cancelled).hasStatusOk();
        assertThat(cancelled).bodyJson().extractingPath("$.booking.status").isEqualTo("CANCELLED_BY_STUDIO");
        entityManager.flush();
        // the trace says the admin did it
        assertThat(jdbc.queryForObject("select cancelled_by from booking where id = ?", Long.class, booking)).isEqualTo(admin);

        assertThat(deactivate()).hasStatusOk();
    }

    @Test
    void theAdminCannotCancelASessionOfAnotherPractitionerThroughThisOne() {
        long marco = data.practitioner("marco@example.com");
        long booking = data.confirmed(marco, data.service(marco, 60), cliente, MONDAY_9, "CARD", null);

        assertThat(mvc.post().uri("/api/admin/practitioners/" + anna + "/bookings/" + booking + "/cancel")
                .with(sessions.user(admin))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // the area of the admin (D-051): a practitioner is not the admin
    @Test
    void onlyTheAdminGetsThere() {
        assertThat(mvc.get().uri("/api/admin/practitioners/" + anna + "/bookings").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ***** the warning of the credits (guide 45, part 6) *****

    @Test
    void theAnswerWarnsOfTheCreditsStillValidWithWhoseTheyAre() {
        long valid = data.creditLot(anna, seduta, cliente, "2027-01-31", 3);
        // not in the warning: expired, used up, another practitioner's
        data.creditLot(anna, seduta, cliente, "2026-11-01", 2);
        long usedUp = data.creditLot(anna, seduta, cliente, "2027-01-31", 1);
        jdbc.update("insert into credit_movement (credit_lot_id, amount, reason, booking_id, created_at) values (?, -1, 'BOOKING', ?, now())",
                usedUp, data.confirmed(anna, seduta, cliente, "2026-11-05T09:00+01:00", "CREDIT", null));
        long marco = data.practitioner("marco@example.com");
        data.creditLot(marco, data.service(marco, 60), cliente, "2027-01-31", 4);

        MvcTestResult result = deactivate();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.outstandingCredits").isEqualTo(java.util.List.of(java.util.Map.of(
                "lotId", (int) valid, "customerId", (int) cliente, "firstName", "Maria", "lastName", "Bianchi",
                "email", "maria@example.com", "serviceId", (int) seduta, "balance", 3, "validUntil", "2027-01-31")));
    }

    // ***** what the customer sees (guide 45, part 7) *****

    @Test
    void theCustomerSeesTheCreditsOfADeactivatedPractitionerAsUnavailable() {
        data.creditLot(anna, seduta, cliente, "2027-01-31", 3);
        data.creditLot(anna, seduta, cliente, "2026-11-01", 2);

        assertThat(deactivate()).hasStatusOk();
        entityManager.flush();
        MvcTestResult afterDeactivation = credits();

        assertThat(afterDeactivation).bodyJson().extractingPath("$[*].status").asArray()
                .containsExactly("EXPIRED", "PRACTITIONER_UNAVAILABLE");

        // computed, not stored: reactivated, the lot is usable again
        assertThat(mvc.post().uri("/api/admin/practitioners/" + anna + "/activate").with(sessions.user(admin)))
                .hasStatusOk();
        entityManager.flush();
        assertThat(credits()).bodyJson().extractingPath("$[*].status").asArray().containsExactly("EXPIRED", "USABLE");
    }

    // ***** helpers *****

    private MvcTestResult deactivate() {
        return mvc.post().uri("/api/admin/practitioners/" + anna + "/deactivate").with(sessions.user(admin)).exchange();
    }

    private MvcTestResult credits() {
        return mvc.get().uri("/api/customer/credits").with(sessions.customer(cliente)).exchange();
    }

    private boolean annaIsActive() {
        return jdbc.queryForObject("select u.active from app_user u join practitioner p on p.user_id = u.id where p.id = ?",
                Boolean.class, anna);
    }
}
