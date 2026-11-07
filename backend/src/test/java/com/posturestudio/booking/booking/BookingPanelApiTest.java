package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;

// The panel answers to requests, from the outside. Today is Friday 6 November 2026, 10:00 in Rome;
// the requests wait until tomorrow at 10:00 (BookingTestData.LATER). Same configuration as
// BookingCustomerApiTest: same context. The card is the provisional LoggingCardHolds
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class BookingPanelApiTest {

    // the provisional identity of the panel (D-028)
    private static final String HEADER = "X-Practitioner-Id";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private String anna;
    private String marco;
    private long waiting;

    @BeforeEach
    void aRequestWaitsForAnna() {
        data = new BookingTestData(jdbc);
        long annaId = data.practitioner("anna@example.com");
        anna = String.valueOf(annaId);
        marco = String.valueOf(data.practitioner("marco@example.com"));
        long seduta = data.service(annaId, 60);
        long cliente = data.customer("cliente@example.com");
        waiting = data.booking(annaId, seduta, cliente, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "PENDING_APPROVAL");
    }

    @Test
    void anApprovalAnswers200WithTheConfirmedBooking() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").header(HEADER, anna))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"id": %d, "customerId": %d, "serviceId": %d,
                         "startsAt": "2026-11-09T09:00:00+01:00", "endsAt": "2026-11-09T10:00:00+01:00",
                         "status": "CONFIRMED", "approvalDeadline": "2026-11-07T10:00:00+01:00"}
                        """.formatted(waiting, customerOf(waiting), serviceOf(waiting)));
    }

    @Test
    void aRejectionAnswers200() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/reject").header(HEADER, anna))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    void anotherPractitionersRequestIsNotFound() {
        // 404 and not 403: the answer must not reveal that the booking exists
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").header(HEADER, marco))
                .hasStatus(HttpStatus.NOT_FOUND);
        String status = jdbc.queryForObject("select status from booking where id = ?", String.class, waiting);
        assertThat(status).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void answeringTwiceAnswers409WithTheCode() {
        mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").header(HEADER, anna).exchange();

        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/reject").header(HEADER, anna))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_TRANSITION_NOT_ALLOWED");
    }

    @Test
    void anAnswerAfterTheDeadlineAnswers409WithTheCode() {
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:30+01:00' where id = ?", waiting);

        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").header(HEADER, anna))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_DEADLINE_PASSED");
    }

    @Test
    void withoutHeaderTheAnswerIs401() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private long customerOf(long booking) {
        return jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
    }

    private long serviceOf(long booking) {
        return jdbc.queryForObject("select service_id from booking where id = ?", Long.class, booking);
    }
}
