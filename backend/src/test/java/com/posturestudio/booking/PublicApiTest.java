package com.posturestudio.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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

import com.jayway.jsonpath.JsonPath;

// The public API of two modules (practitioner and catalog), as the first page of the site uses it:
// no X-Practitioner-Id header anywhere in this class
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class PublicApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long rossi;
    private long bianchi;
    private long verdi;

    @BeforeEach
    void insertPractitioners() {
        rossi = insertPractitioner("anna@example.com", "Anna", "Rossi", true);
        bianchi = insertPractitioner("luca@example.com", "Luca", "Bianchi", true);
        // deactivated account, but with a service on sale
        verdi = insertPractitioner("marco@example.com", "Marco", "Verdi", false);
        insertService(verdi, "Seduta di Marco", true);
    }

    // ***** PRACTITIONERS *****

    @Test
    void listShowsOnlyActivePractitionersSortedByLastName() {
        assertThat(mvc.get().uri("/api/public/practitioners"))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly((int) bianchi, (int) rossi);
    }

    @Test
    void practitionerExposesOnlyIdAndName() throws Exception {
        String body = mvc.get().uri("/api/public/practitioners").exchange().getResponse().getContentAsString();

        // the exact set of fields: a new one must be a decision, not an accident
        Map<String, Object> first = JsonPath.read(body, "$[0]");
        assertThat(first.keySet()).containsExactlyInAnyOrder("id", "firstName", "lastName");
        assertThat(first).containsEntry("firstName", "Luca").containsEntry("lastName", "Bianchi");
    }

    // ***** CATALOG *****

    @Test
    void catalogShowsOnlyWhatIsOnSale() {
        // arrange
        long active = insertService(rossi, "Seduta posturale", true);
        long deactivated = insertService(rossi, "Vecchio servizio", false);
        long onSale = insertPackage(rossi, active, "5 sedute", true);
        insertPackage(rossi, active, "10 sedute", false);
        insertPackage(rossi, deactivated, "Pacchetto del vecchio servizio", true);
        insertService(bianchi, "Seduta di Luca", true);

        // act
        MvcTestResult result = mvc.get().uri("/api/public/practitioners/" + rossi + "/catalog").exchange();

        // assert
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.services[*].id").asArray().containsExactly((int) active);
        assertThat(result).bodyJson().extractingPath("$.packages[*].id").asArray().containsExactly((int) onSale);
    }

    @Test
    void catalogExposesNoActiveFlagAndNoPractitioner() throws Exception {
        // arrange
        long serviceId = insertService(rossi, "Seduta posturale", true);
        insertPackage(rossi, serviceId, "5 sedute", true);

        // act
        String body = mvc.get().uri("/api/public/practitioners/" + rossi + "/catalog")
                .exchange().getResponse().getContentAsString();

        // assert
        Map<String, Object> catalog = JsonPath.read(body, "$");
        Map<String, Object> service = JsonPath.read(body, "$.services[0]");
        Map<String, Object> offer = JsonPath.read(body, "$.packages[0]");
        assertThat(catalog.keySet()).containsExactlyInAnyOrder("services", "packages");
        assertThat(service.keySet()).containsExactlyInAnyOrder("id", "name", "durationMinutes", "priceCents");
        assertThat(offer.keySet()).containsExactlyInAnyOrder(
                "id", "serviceId", "name", "sessionsCount", "priceCents", "validityDays");
    }

    @Test
    void catalogOfDeactivatedPractitionerIs404EvenWithServicesOnSale() {
        assertThat(mvc.get().uri("/api/public/practitioners/" + verdi + "/catalog"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType("application/problem+json");
    }

    @Test
    void deactivatedAndMissingPractitionerLookTheSame() throws Exception {
        // 404 for both, and the same detail: the answer must not reveal that Verdi exists
        long neverUsed = verdi + 1000;

        MvcTestResult deactivated = mvc.get().uri("/api/public/practitioners/" + verdi + "/catalog").exchange();
        MvcTestResult missing = mvc.get().uri("/api/public/practitioners/" + neverUsed + "/catalog").exchange();

        assertThat(deactivated).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
        String deactivatedDetail = JsonPath.read(deactivated.getResponse().getContentAsString(), "$.detail");
        String missingDetail = JsonPath.read(missing.getResponse().getContentAsString(), "$.detail");
        assertThat(deactivatedDetail.replace(String.valueOf(verdi), "ID"))
                .isEqualTo(missingDetail.replace(String.valueOf(neverUsed), "ID"));
    }

    @Test
    void nonNumericIdIs400AndNot500() {
        // MethodArgumentTypeMismatchException: handled by ResponseEntityExceptionHandler,
        // it must not fall into our generic 500
        assertThat(mvc.get().uri("/api/public/practitioners/abc/catalog"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType("application/problem+json");
    }

    // ***** HELPERS *****

    private long insertPractitioner(String email, String firstName, String lastName, boolean active) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('PRACTITIONER', ?, ?) returning id",
                Long.class, email, active);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, ?, ?) returning id",
                Long.class, userId, firstName, lastName);
    }

    private long insertService(long practitionerId, String name, boolean active) {
        return jdbc.queryForObject("""
                insert into service (practitioner_id, name, duration_minutes, price_cents, active)
                values (?, ?, 60, 3000, ?) returning id
                """, Long.class, practitionerId, name, active);
    }

    private long insertPackage(long practitionerId, long serviceId, String name, boolean active) {
        return jdbc.queryForObject("""
                insert into package_offer (practitioner_id, service_id, name, sessions_count, price_cents, validity_days, active)
                values (?, ?, ?, 5, 14000, 90, ?) returning id
                """, Long.class, practitionerId, serviceId, name, active);
    }
}
