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
import com.posturestudio.booking.TestcontainersConfiguration;

// The customer API from the outside. Today is Friday 6 November 2026, 10:00 in Rome.
// Same configuration as SchedulePublicApiTest: same Spring context, same container
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class BookingCustomerApiTest {

    // the provisional identity of the customer (guide 19, part 7)
    private static final String HEADER = "X-Customer-Id";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private String cliente;
    private String altroCliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = String.valueOf(data.customer("cliente@example.com"));
        altroCliente = String.valueOf(data.customer("altro@example.com"));
    }

    @Test
    void withoutACustomerTheAnswerIs401() {
        assertThat(mvc.post().uri("/api/customer/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(body("2026-11-09T09:00:00+01:00")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/customer/bookings/1").header(HEADER, "999999"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRequestAnswers201WithLocationAndTheBooking() throws Exception {
        MvcTestResult result = request(cliente, "2026-11-09T09:00:00+01:00");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        int id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result).hasHeader("Location", "/api/customer/bookings/" + id);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id": %d, "practitionerId": %d, "serviceId": %d,
                 "startsAt": "2026-11-09T09:00:00+01:00", "endsAt": "2026-11-09T10:00:00+01:00",
                 "status": "PENDING_PAYMENT", "priceCents": 3000, "holdExpiresAt": "2026-11-06T10:30:00+01:00"}
                """.formatted(id, anna, seduta));
    }

    @Test
    void aStartWithAnotherOffsetIsTheSameInstant() {
        // 8:00 UTC is 9:00 in Rome: the backend compares instants, not the text the client wrote
        assertThat(request(cliente, "2026-11-09T08:00:00Z"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.startsAt").isEqualTo("2026-11-09T09:00:00+01:00");
    }

    @Test
    void aMissingStartAnswers400OnItsField() {
        MvcTestResult result = mvc.post().uri("/api/customer/bookings").header(HEADER, cliente)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d}
                        """.formatted(anna, seduta))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("startsAt");
    }

    @Test
    void aTakenSlotAnswers409WithItsCode() {
        request(altroCliente, "2026-11-09T09:00:00+01:00");

        assertThat(request(cliente, "2026-11-09T09:00:00+01:00"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("SLOT_NOT_AVAILABLE");
    }

    @Test
    void theLimitAnswers409WithItsCode() {
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        request(cliente, "2026-11-09T09:00:00+01:00");

        assertThat(request(cliente, "2026-11-10T09:00:00+01:00"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_LIMIT_REACHED");
    }

    @Test
    void aServiceNotOnSaleAnswers404() {
        jdbc.update("update service set active = false where id = ?", seduta);

        assertThat(request(cliente, "2026-11-09T09:00:00+01:00")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCustomerReadsTheirBookingButNotSomeoneElses() throws Exception {
        MvcTestResult created = request(cliente, "2026-11-09T09:00:00+01:00");
        int id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        assertThat(mvc.get().uri("/api/customer/bookings/" + id).header(HEADER, cliente))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("PENDING_PAYMENT");
        // 404 and not 403: the answer must not reveal that the booking exists
        assertThat(mvc.get().uri("/api/customer/bookings/" + id).header(HEADER, altroCliente))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult request(String customer, String startsAt) {
        return mvc.post().uri("/api/customer/bookings").header(HEADER, customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(startsAt))
                .exchange();
    }

    private String body(String startsAt) {
        return """
                {"practitionerId": %d, "serviceId": %d, "startsAt": "%s"}
                """.formatted(anna, seduta, startsAt);
    }
}
