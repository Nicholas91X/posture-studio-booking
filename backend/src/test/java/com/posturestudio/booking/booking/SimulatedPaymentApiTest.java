package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The PROVISIONAL simulated payment, with its switch on (guide 20, part 5). A context of its own, because
// the property changes which beans exist; BookingCustomerApiTest proves that without it the URL is a 404.
// Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest(properties = "booking.simulated-payments=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class SimulatedPaymentApiTest {

    @Autowired
    MockMvcTester mvc;

    // signed-in requests: a real session and a CSRF token (guide 25, part 8)
    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long seduta;
    private String cliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = String.valueOf(data.customer("cliente@example.com"));
    }

    @Test
    void aSimulatedPaymentTakesTheRequestToThePractitioner() throws Exception {
        MvcTestResult requested = mvc.post().uri("/api/customer/bookings").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d, "startsAt": "2026-11-09T09:00:00+01:00"}
                        """.formatted(anna, seduta))
                .exchange();
        int id = JsonPath.read(requested.getResponse().getContentAsString(), "$.id");

        MvcTestResult paid = mvc.post().uri("/api/dev/bookings/" + id + "/payment-authorized").exchange();

        assertThat(paid).hasStatusOk();
        assertThat(paid).bodyJson().extractingPath("$.status").isEqualTo("PENDING_APPROVAL");
        // requested at 10:00, window of 12 hours (default of V4)
        assertThat(paid).bodyJson().extractingPath("$.approvalDeadline").isEqualTo("2026-11-06T22:00:00+01:00");
    }

    @Test
    void anUnknownBookingAnswers404() {
        assertThat(mvc.post().uri("/api/dev/bookings/999999/payment-authorized")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
