package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

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

// What the customer reads of their credits (guide 41, parts 1 and 3): their lots with the state decided by the
// backend, one lot with its history, never the notes of the practitioner, never the lots of another.
// Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class CustomerCreditsApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long annasUser;
    private long seduta;
    private long cliente;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        seduta = data.service(anna);
        cliente = data.customer("cliente@example.com");
    }

    @Test
    void myLotsWithTheirStateTheOneThatEndsFirstFirst() {
        long later = lot(cliente, "2027-01-31", 3);
        long usedUp = lot(cliente, "2026-12-31", 1);
        data.adjustment(usedUp, -1, annasUser);
        long expired = lot(cliente, "2026-11-05", 2);
        // today is the last day: still usable until midnight in Rome
        long today = lot(cliente, "2026-11-06", 1);

        MvcTestResult result = mvc.get().uri("/api/customer/credits").with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) expired, (int) today, (int) usedUp, (int) later);
        assertThat(result).bodyJson().extractingPath("$[*].status").asArray()
                .containsExactly("EXPIRED", "USABLE", "USED_UP", "USABLE");
        assertThat(result).bodyJson().extractingPath("$[3].balance").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$[3].validUntil").isEqualTo("2027-01-31");
        assertThat(result).bodyJson().extractingPath("$[3].practitionerId").isEqualTo((int) anna);
        assertThat(result).bodyJson().extractingPath("$[3].serviceId").isEqualTo((int) seduta);
    }

    @Test
    void theLotsOfAnotherCustomerAreNotMine() {
        long altro = data.customer("altro@example.com");
        long hisLot = lot(altro, "2027-01-31", 3);

        assertThat(mvc.get().uri("/api/customer/credits").with(sessions.customer(cliente)))
                .bodyJson().extractingPath("$").asArray().isEmpty();
        assertThat(mvc.get().uri("/api/customer/credits/" + hisLot).with(sessions.customer(cliente)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // movements and extensions in one history, in order of time; the why of the practitioner never reaches the
    // customer (D-073)
    @Test
    void aLotWithItsHistoryWithoutTheNotes() throws Exception {
        long lot = lot(cliente, "2026-12-31", 3);
        long booking = data.creditBooking(anna, seduta, cliente);
        movementAt(lot, -1, "BOOKING", booking, null, null, "2026-11-02T10:00:00Z");
        movementAt(lot, 1, "BOOKING_RELEASE", booking, null, null, "2026-11-03T10:00:00Z");
        movementAt(lot, -1, "ADJUSTMENT", null, annasUser, "Nota interna sulla correzione", "2026-11-04T10:00:00Z");
        jdbc.update("""
                insert into credit_lot_extension (credit_lot_id, old_valid_until, new_valid_until, extended_by, note,
                                                  created_at)
                values (?, '2026-12-31', '2027-03-31', ?, 'Nota interna sulla proroga', '2026-11-05T10:00:00Z')
                """, lot, annasUser);
        jdbc.update("update credit_lot set valid_until = '2027-03-31' where id = ?", lot);

        MvcTestResult result = mvc.get().uri("/api/customer/credits/" + lot).with(sessions.customer(cliente)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.lot.balance").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.lot.validUntil").isEqualTo("2027-03-31");
        assertThat(result).bodyJson().extractingPath("$.history[*].kind").asArray()
                .containsExactly("LOT_CREATED", "BOOKING", "BOOKING_RELEASE", "ADJUSTMENT", "EXTENSION");
        assertThat(result).bodyJson().extractingPath("$.history[0].amount").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.history[0].source").isEqualTo("GRANT");
        assertThat(result).bodyJson().extractingPath("$.history[1].bookingId").isEqualTo((int) booking);
        assertThat(result).bodyJson().extractingPath("$.history[1].at").isEqualTo("2026-11-02T11:00:00+01:00");
        assertThat(result).bodyJson().extractingPath("$.history[4].oldValidUntil").isEqualTo("2026-12-31");
        assertThat(result).bodyJson().extractingPath("$.history[4].newValidUntil").isEqualTo("2027-03-31");
        assertThat(result).bodyJson().extractingPath("$.history[*].note").asArray().containsOnlyNulls();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Nota interna").doesNotContain("Prova");
    }

    // ***** who can read it (D-029, D-051) *****

    @Test
    void withoutASessionTheAnswerIs401() {
        assertThat(mvc.get().uri("/api/customer/credits").with(csrf())).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPractitionerIsNotACustomer() {
        assertThat(mvc.get().uri("/api/customer/credits").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ***** helpers *****

    // a gift of Anna (its note: "Prova"), born on 1 November
    private long lot(long customer, String validUntil, int credits) {
        long lot = jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, 'Prova', ?::date, '2026-11-01T10:00:00Z')
                returning id
                """, Long.class, customer, anna, seduta, annasUser, validUntil);
        movementAt(lot, credits, "LOT_CREATED", null, null, null, "2026-11-01T10:00:00Z");
        return lot;
    }

    private void movementAt(long lot, int amount, String reason, Long booking, Long createdBy, String note, String at) {
        jdbc.update("""
                insert into credit_movement (credit_lot_id, amount, reason, booking_id, created_by, note, created_at)
                values (?, ?, ?, ?, ?, ?, ?::timestamptz)
                """, lot, amount, reason, booking, createdBy, note, at);
    }
}
