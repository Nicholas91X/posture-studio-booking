package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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

// A package sold in the studio (D-089, V17): a lot of its own source, photographed from the package, with who sold
// it, and no payment. Today is Friday 6 November 2026, 10:00 in Rome; the package: 5 sessions, 120 euro, 90 days
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
class StudioSaleApiTest {

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
    private long pacchetto;
    private long marco;

    @BeforeEach
    void annaSellsAPackage() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        seduta = data.service(anna);
        pacchetto = data.packageOffer(anna, seduta);
        marco = data.practitioner("marco@example.com");
    }

    @Test
    void aSaleCreatesALotOfItsOwnSourceWithThePhotographAndWhoSoldIt() {
        MvcTestResult result = sell(pacchetto, "nuova@example.com");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long lot = idOf(result);
        assertThat(result).hasHeader("Location", "/api/panel/credit-lots/" + lot);
        assertThat(result).bodyJson().extractingPath("$.source").isEqualTo("STUDIO_SALE");
        assertThat(result).bodyJson().extractingPath("$.balance").isEqualTo(5);
        // 90 days from today in Rome, as a package bought online from the day of its payment (D-070)
        assertThat(result).bodyJson().extractingPath("$.validUntil").isEqualTo("2027-02-04");
        Map<String, Object> row = jdbc.queryForMap("select * from credit_lot where id = ?", lot);
        assertThat(row).containsEntry("source", "STUDIO_SALE")
                .containsEntry("sold_by", annasUser)
                .containsEntry("package_offer_id", pacchetto)
                .containsEntry("price_cents", 12000L)
                .containsEntry("practitioner_id", anna)
                .containsEntry("service_id", seduta);
        assertThat(row.get("payment_id")).isNull();
        assertThat(row.get("granted_by")).isNull();
        assertThat(row.get("note")).isNull();
        // the system does not record cash: no payment at all (D-089)
        assertThat(jdbc.queryForObject("select count(*) from payment", Long.class)).isZero();
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // the photograph: changing the package afterwards changes nothing in the lot sold (D-011)
    @Test
    void theLotKeepsWhatThePackageWasAtTheSale() {
        long lot = idOf(sell(pacchetto, "cliente@example.com"));

        jdbc.update("update package_offer set price_cents = 15000, sessions_count = 10, validity_days = 365 where id = ?",
                pacchetto);

        assertThat(jdbc.queryForObject("select price_cents from credit_lot where id = ?", Long.class, lot)).isEqualTo(12000L);
        assertThat(jdbc.queryForObject("select valid_until from credit_lot where id = ?", LocalDate.class, lot))
                .isEqualTo(LocalDate.parse("2027-02-04"));
        assertThat(data.balance(lot)).isEqualTo(5);
    }

    // another practitioner's package is "not found" (D-026); a package off sale cannot be sold
    @Test
    void anotherPractitionersOrAnInactivePackageIsNotFound() {
        long marcosPackage = data.packageOffer(marco, data.service(marco));
        assertThat(sell(marcosPackage, "cliente@example.com")).hasStatus(HttpStatus.NOT_FOUND);

        jdbc.update("update package_offer set active = false where id = ?", pacchetto);
        assertThat(sell(pacchetto, "cliente@example.com")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("select count(*) from credit_lot", Long.class)).isZero();
    }

    @Test
    void theCustomerSeesTheLotAsSoldInTheStudio() {
        sell(pacchetto, "cliente@example.com");
        long cliente = jdbc.queryForObject("select c.id from customer c join app_user u on u.id = c.user_id"
                + " where u.email = 'cliente@example.com'", Long.class);

        assertThat(mvc.get().uri("/api/customer/credits").with(sessions.customer(cliente)))
                .hasStatusOk()
                .bodyJson().extractingPath("$[0].source").isEqualTo("STUDIO_SALE");
    }

    // V17: a sale always has who sold it. One violation per test: after it the transaction is aborted
    @Test
    void theDatabaseDemandsWhoSold() {
        long cliente = data.customer("cliente@example.com");

        assertViolates("credit_lot_sale_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, package_offer_id, price_cents,
                                        valid_until, created_at)
                values (?, ?, ?, 'STUDIO_SALE', ?, 12000, '2027-02-04', now())
                """, cliente, anna, seduta, pacchetto));
    }

    // and the photograph of the package: which one and its price
    @Test
    void theDatabaseDemandsThePackageAndItsPrice() {
        long cliente = data.customer("cliente@example.com");

        assertViolates("credit_lot_package_columns_ck", () -> jdbc.update("""
                insert into credit_lot (customer_id, practitioner_id, service_id, source, sold_by, valid_until, created_at)
                values (?, ?, ?, 'STUDIO_SALE', ?, '2027-02-04', now())
                """, cliente, anna, seduta, annasUser));
    }

    private MvcTestResult sell(long packageId, String email) {
        return mvc.post().uri("/api/panel/package-sales").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"packageOfferId": %d, "customer": {"email": "%s", "firstName": "Nuova", "lastName": "Cliente"}}
                        """.formatted(packageId, email))
                .exchange();
    }

    private static long idOf(MvcTestResult result) {
        try {
            return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertViolates(String constraintName, Runnable statement) {
        var exception = Assertions.assertThrows(DataIntegrityViolationException.class, statement::run);
        assertThat(exception.getMessage()).contains(constraintName);
    }
}
