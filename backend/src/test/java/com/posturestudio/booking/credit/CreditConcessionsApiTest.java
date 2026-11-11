package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;

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

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

import jakarta.persistence.EntityManager;

// The concessions from the panel (D-067, guide 40): gift, extension, correction, with their trace, on own lots
// only. Real sessions; the rows by SQL. Today is Friday 6 November 2026, 10:00 in Rome
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class CreditConcessionsApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    private CreditTestData data;
    private long anna;
    private long annasUser;
    private long seduta;
    private long marco;

    @BeforeEach
    void annaAndMarco() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        seduta = data.service(anna);
        marco = data.practitioner("marco@example.com");
    }

    // ***** gift (guide 40, part 3) *****

    @Test
    void aGiftToANewCustomerCreatesTheCustomerAndALotWithItsTrace() throws Exception {
        MvcTestResult result = grant("nuova@example.com", seduta, 2, "2026-12-31", "Seduta saltata per un nostro imprevisto");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long lot = idOf(result);
        assertThat(result).hasHeader("Location", "/api/panel/credit-lots/" + lot);
        assertThat(result).bodyJson().extractingPath("$.source").isEqualTo("GRANT");
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.validUntil").isEqualTo("2026-12-31");
        Map<String, Object> row = jdbc.queryForMap("select * from credit_lot where id = ?", lot);
        assertThat(row).containsEntry("granted_by", annasUser)
                .containsEntry("note", "Seduta saltata per un nostro imprevisto")
                .containsEntry("practitioner_id", anna).containsEntry("service_id", seduta);
        assertThat(jdbc.queryForObject("select c.first_name from customer c join app_user u on u.id = c.user_id"
                + " where u.email = 'nuova@example.com'", String.class)).isEqualTo("Nuovo");
    }

    // D-048: an existing customer keeps their name, whatever the practitioner typed
    @Test
    void aGiftToAnExistingCustomerKeepsTheirName() {
        long cliente = data.customer("cliente@example.com");

        long lot = idOf(grant("cliente@example.com", seduta, 1, null, "Omaggio"));

        assertThat(jdbc.queryForObject("select customer_id from credit_lot where id = ?", Long.class, lot)).isEqualTo(cliente);
        assertThat(jdbc.queryForObject("select first_name from customer where id = ?", String.class, cliente))
                .isEqualTo("Test");
    }

    // D-067: missing, the last valid day of a single session: today + 90 days
    @Test
    void withoutADateAGiftLastsAsASingleSession() {
        assertThat(grant("cliente@example.com", seduta, 1, null, "Omaggio"))
                .bodyJson().extractingPath("$.validUntil").isEqualTo("2027-02-04");
    }

    // today is fine: it lasts until midnight in Rome
    @Test
    void aGiftUntilTodayIsAccepted() {
        assertThat(grant("cliente@example.com", seduta, 1, "2026-11-06", "Omaggio")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aGiftUntilADayAlreadyGoneIsRefused() {
        MvcTestResult result = grant("cliente@example.com", seduta, 1, "2026-11-05", "Omaggio");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CREDIT_VALID_UNTIL_IN_THE_PAST");
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where practitioner_id = ?", Long.class, anna)).isZero();
    }

    @Test
    void aGiftOnAServiceOfAnotherPractitionerIsNotFound() {
        assertThat(grant("cliente@example.com", data.service(marco), 1, null, "Omaggio")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aGiftOnADeactivatedServiceIsNotFound() {
        jdbc.update("update service set active = false where id = ?", seduta);

        assertThat(grant("cliente@example.com", seduta, 1, null, "Omaggio")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void aGiftSaysWhy() {
        assertThat(grant("cliente@example.com", seduta, 1, null, "  ")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aGiftHasAtLeastOneSession() {
        assertThat(grant("cliente@example.com", seduta, 0, null, "Omaggio")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyAPractitionerGrants() {
        long cliente = data.customer("cliente@example.com");

        assertThat(mvc.post().uri("/api/panel/credit-grants").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON).content(grantBody("cliente@example.com", seduta, 1, null, "x")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // ***** extension (guide 40, part 4) *****

    @Test
    void anExtensionMovesTheLastDayForwardAndLeavesItsHistory() {
        long lot = annasLot("2026-12-31", 3);

        MvcTestResult result = extend(lot, "2027-03-31", "Il cliente era malato");

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.validUntil").isEqualTo("2027-03-31");
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(3);
        entityManager.flush();
        assertThat(jdbc.queryForMap("select old_valid_until::text as old, new_valid_until::text as new, extended_by, note"
                + " from credit_lot_extension where credit_lot_id = ?", lot))
                .containsEntry("old", "2026-12-31").containsEntry("new", "2027-03-31")
                .containsEntry("extended_by", annasUser).containsEntry("note", "Il cliente era malato");
        assertThat(jdbc.queryForObject("select valid_until::text from credit_lot where id = ?", String.class, lot))
                .isEqualTo("2027-03-31");
    }

    // "my two credits expired, can you give them back?": the typical case
    @Test
    void anExpiredLotCanBeExtended() {
        long lot = annasLot("2026-10-31", 2);

        assertThat(extend(lot, "2026-12-31", "Scaduti durante le ferie dello studio")).hasStatusOk();
    }

    @Test
    void anExtensionMovesForwardOnly() {
        long lot = annasLot("2026-12-31", 3);

        MvcTestResult result = extend(lot, "2026-12-31", "Niente");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CREDIT_EXTENSION_NOT_LATER");
    }

    // an expired lot moved to another day already gone gives nothing back
    @Test
    void anExtensionToADayAlreadyGoneIsRefused() {
        long lot = annasLot("2026-10-01", 2);

        assertThat(extend(lot, "2026-10-05", "Ferie")).bodyJson().extractingPath("$.code")
                .isEqualTo("CREDIT_VALID_UNTIL_IN_THE_PAST");
    }

    @Test
    void theLotOfAnotherPractitionerIsNotFound() {
        long cliente = data.customer("cliente@example.com");
        long marcosLot = data.lotWith(cliente, marco, data.service(marco), 3);

        assertThat(extend(marcosLot, "2027-12-31", "x")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(adjust(marcosLot, -1, "x")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/panel/credit-lots/" + marcosLot).with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** correction (guide 40, part 5) *****

    @Test
    void aCorrectionIsAMovementWithItsTrace() {
        long lot = annasLot("2026-12-31", 3);

        assertThat(adjust(lot, -1, "Seduta fatta prima del sistema")).bodyJson().extractingPath("$.balance").isEqualTo(2);
        assertThat(adjust(lot, 2, "Errore di prima")).bodyJson().extractingPath("$.balance").isEqualTo(4);

        assertThat(jdbc.queryForList("select reason || ' ' || amount || ' ' || created_by || ' ' || note from credit_movement"
                + " where credit_lot_id = ? and reason = 'ADJUSTMENT' order by id", String.class, lot))
                .containsExactly("ADJUSTMENT -1 " + annasUser + " Seduta fatta prima del sistema",
                        "ADJUSTMENT 2 " + annasUser + " Errore di prima");
    }

    @Test
    void aCorrectionNeverGoesBelowZero() {
        long lot = annasLot("2026-12-31", 1);

        MvcTestResult result = adjust(lot, -2, "Troppi");

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CREDIT_BALANCE_INSUFFICIENT");
        assertThat(data.balance(lot)).isEqualTo(1);
    }

    @Test
    void aCorrectionOfZeroIsRefused() {
        long lot = annasLot("2026-12-31", 1);

        assertThat(adjust(lot, 0, "Niente")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aCorrectionSaysWhy() {
        long lot = annasLot("2026-12-31", 1);

        assertThat(adjust(lot, -1, "")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anOwnLotIsReadBack() {
        long lot = annasLot("2026-12-31", 3);

        assertThat(mvc.get().uri("/api/panel/credit-lots/" + lot).with(sessions.practitioner(anna)))
                .hasStatusOk().bodyJson().extractingPath("$.balance").isEqualTo(3);
    }

    // ***** helpers *****

    private long annasLot(String validUntil, int credits) {
        long cliente = data.customer("lotto-" + validUntil + "@example.com");
        long lot = jdbc.queryForObject("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, granted_by, note,
                                        valid_until, created_at)
                values (?, ?, ?, 'GRANT', ?, 'Prova', ?::date, now())
                returning id
                """, Long.class, cliente, anna, seduta, annasUser, validUntil);
        data.lotCreated(lot, credits);
        return lot;
    }

    private MvcTestResult grant(String email, long serviceId, int credits, String validUntil, String note) {
        return mvc.post().uri("/api/panel/credit-grants").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON).content(grantBody(email, serviceId, credits, validUntil, note))
                .exchange();
    }

    private static String grantBody(String email, long serviceId, int credits, String validUntil, String note) {
        return """
                {"customer": {"email": "%s", "firstName": "Nuovo", "lastName": "Cliente"},
                 "serviceId": %d, "credits": %d, "validUntil": %s, "note": "%s"}
                """.formatted(email, serviceId, credits, validUntil == null ? "null" : "\"" + validUntil + "\"", note);
    }

    private MvcTestResult extend(long lot, String validUntil, String note) {
        return mvc.post().uri("/api/panel/credit-lots/" + lot + "/extensions").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"validUntil\": \"" + validUntil + "\", \"note\": \"" + note + "\"}").exchange();
    }

    private MvcTestResult adjust(long lot, int amount, String note) {
        return mvc.post().uri("/api/panel/credit-lots/" + lot + "/adjustments").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + ", \"note\": \"" + note + "\"}").exchange();
    }

    private static long idOf(MvcTestResult result) {
        try {
            return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
