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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// What the practitioner reads (guide 41, part 4): "my customers" (D-066) and the credits of one of them, only the
// own. The confidentiality of the brief, as at step 8: Anna never sees what is only Marco's.
// Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class PanelCustomersApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long annasSeduta;
    private long marco;
    private long marcosSeduta;

    @BeforeEach
    void annaAndMarco() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasSeduta = data.service(anna, 60);
        marco = data.practitioner("marco@example.com");
        marcosSeduta = data.service(marco, 60);
    }

    // ***** my customers (D-066) *****

    @Test
    void myCustomersHaveABookingOrALotWithMeInOrderOfName() throws Exception {
        long withBooking = data.customer("prenota@example.com", "Bruno", "Verdi");
        long withLot = data.customer("lotto@example.com", "Carla", "Bianchi");
        long withBoth = data.customer("tutti@example.com", "Aldo", "Bianchi");
        long onlyMarcos = data.customer("marco-only@example.com", "Dario", "Neri");
        // a booking in any state counts, even one that never went anywhere
        data.booking(anna, annasSeduta, withBooking, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "EXPIRED");
        data.creditLot(anna, annasSeduta, withLot, "2026-12-31", 3);
        data.booking(anna, annasSeduta, withBoth, "2026-11-10T09:00+01:00", "2026-11-10T10:00+01:00", "CONFIRMED");
        data.creditLot(anna, annasSeduta, withBoth, "2026-12-31", 3);
        data.booking(marco, marcosSeduta, onlyMarcos, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "CONFIRMED");
        data.creditLot(marco, marcosSeduta, onlyMarcos, "2026-12-31", 3);

        MvcTestResult result = mvc.get().uri("/api/panel/customers").with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        // by last name then first name; the one with both appears once
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) withBoth, (int) withLot, (int) withBooking);
        assertThat(result).bodyJson().extractingPath("$[0].firstName").isEqualTo("Aldo");
        assertThat(result).bodyJson().extractingPath("$[0].lastName").isEqualTo("Bianchi");
        assertThat(result).bodyJson().extractingPath("$[0].email").isEqualTo("tutti@example.com");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("marco-only").doesNotContain("Neri");
    }

    @Test
    void withoutCustomersTheListIsEmpty() {
        assertThat(mvc.get().uri("/api/panel/customers").with(sessions.practitioner(anna)))
                .hasStatusOk().bodyJson().extractingPath("$").asArray().isEmpty();
    }

    // ***** the credits of one of them *****

    // a customer of both: Anna sees her lots, never Marco's
    @Test
    void theCreditsOfACustomerInCommonAreOnlyMine() {
        long comune = data.customer("comune@example.com");
        long annasLot = data.creditLot(anna, annasSeduta, comune, "2026-12-31", 3);
        long marcosLot = data.creditLot(marco, marcosSeduta, comune, "2026-12-31", 5);

        MvcTestResult result = mvc.get().uri("/api/panel/customers/" + comune + "/credits")
                .with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) annasLot);
        assertThat(result).bodyJson().extractingPath("$[0].balance").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$[0].status").isEqualTo("USABLE");
        assertThat(mvc.get().uri("/api/panel/credit-lots/" + marcosLot).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // the practitioner reads the why of their own concessions (the trace, D-067)
    @Test
    void myLotShowsItsHistoryWithTheNotes() {
        long cliente = data.customer("cliente@example.com");
        long lot = data.creditLot(anna, annasSeduta, cliente, "2026-12-31", 3);

        MvcTestResult result = mvc.get().uri("/api/panel/credit-lots/" + lot).with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.history[0].kind").isEqualTo("LOT_CREATED");
        assertThat(result).bodyJson().extractingPath("$.history[0].note").isEqualTo("Prova");
    }

    @Test
    void aCustomerIsNotAPractitioner() {
        long cliente = data.customer("cliente@example.com");

        assertThat(mvc.get().uri("/api/panel/customers").with(sessions.customer(cliente))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/panel/customers/" + cliente + "/credits").with(sessions.customer(cliente)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
