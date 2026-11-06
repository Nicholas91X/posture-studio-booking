package com.posturestudio.booking.catalog;

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
import com.posturestudio.booking.TestcontainersConfiguration;

// The whole application, a real PostgreSQL, requests through MockMvc in the test's own thread:
// @Transactional rolls everything back at the end, as in @DataJpaTest
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
public class CatalogPanelApiTest {

    // the provisional identity of the panel (D-028)
    private static final String HEADER = "X-Practitioner-Id";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String anna;
    private String marco;

    @BeforeEach
    void insertPractitioners() {
        anna = String.valueOf(insertPractitioner("anna@example.com"));
        marco = String.valueOf(insertPractitioner("marco@example.com"));
    }

    // ***** WHO IS ACTING *****

    @Test
    void withoutHeaderTheAnswerIs401() {
        assertThat(mvc.get().uri("/api/panel/services"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType("application/problem+json");
    }

    @Test
    void unknownOrInvalidPractitionerIs401() {
        assertThat(mvc.get().uri("/api/panel/services").header(HEADER, "999999"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/panel/services").header(HEADER, "abc"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // ***** CREATE AND VALIDATE *****

    @Test
    void createdServiceAnswers201WithLocation() throws Exception {
        // act
        MvcTestResult result = createService(anna, """
                {"name": "Seduta posturale", "durationMinutes": 60, "priceCents": 3000}
                """);

        // assert
        assertThat(result).hasStatus(HttpStatus.CREATED);
        int id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result).hasHeader("Location", "/api/panel/services/" + id);
        assertThat(result).bodyJson().extractingPath("$.active").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.priceCents").isEqualTo(3000);
    }

    @Test
    void invalidServiceAnswers400WithTheWrongFields() {
        MvcTestResult result = createService(anna, """
                {"name": "  ", "durationMinutes": 60, "priceCents": 0}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("name", "priceCents");
        assertThat(result).bodyJson().extractingPath("$.errors[?(@.field == 'priceCents')].constraint").asArray()
                .containsExactly("Positive");
    }

    @Test
    void missingNumberIsReportedOnItsField() {
        // with an int component, Jackson 3 fails the whole reading ("Failed to read request", no field):
        // the record uses Integer, so the missing value arrives as null and is reported here
        MvcTestResult result = createService(anna, """
                {"name": "Seduta posturale", "priceCents": 3000}
                """);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("durationMinutes");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("NotNull");
    }

    @Test
    void unreadableJsonAnswers400() {
        assertThat(createService(anna, "{ not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType("application/problem+json");
    }

    @Test
    void packageOfOneSessionAnswers400() throws Exception {
        long serviceId = createServiceId(anna);

        MvcTestResult result = createPackage(anna, serviceId, 1);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("sessionsCount");
    }

    @Test
    void packageWithoutServiceAnswers400() {
        MvcTestResult result = mvc.post().uri("/api/panel/packages").header(HEADER, anna)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "5 sedute", "sessionsCount": 5, "priceCents": 14000, "validityDays": 90}
                        """)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("serviceId");
    }

    // ***** OWNERSHIP: A NEVER TOUCHES WHAT IS B'S *****

    @Test
    void practitionerCannotUpdateServiceOfAnother() throws Exception {
        // arrange
        long marcosService = createServiceId(marco);

        // act
        MvcTestResult result = mvc.put().uri("/api/panel/services/" + marcosService).header(HEADER, anna)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Rubato", "durationMinutes": 60, "priceCents": 1}
                        """)
                .exchange();

        // assert
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).hasContentType("application/problem+json");
        Long price = jdbc.queryForObject("select price_cents from service where id = ?", Long.class, marcosService);
        assertThat(price).isEqualTo(3000);
    }

    @Test
    void someoneElsesServiceAndMissingOneLookTheSame() throws Exception {
        // 404 and not 403: the answer must not reveal that Marco's service exists
        long marcosService = createServiceId(marco);
        long neverUsed = marcosService + 1000;

        MvcTestResult others = mvc.post().uri("/api/panel/services/" + marcosService + "/deactivate").header(HEADER, anna).exchange();
        MvcTestResult missing = mvc.post().uri("/api/panel/services/" + neverUsed + "/deactivate").header(HEADER, anna).exchange();

        assertThat(others).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
        String othersDetail = JsonPath.read(others.getResponse().getContentAsString(), "$.detail");
        String missingDetail = JsonPath.read(missing.getResponse().getContentAsString(), "$.detail");
        assertThat(othersDetail.replace(String.valueOf(marcosService), "ID"))
                .isEqualTo(missingDetail.replace(String.valueOf(neverUsed), "ID"));
    }

    @Test
    void listShowsOnlyOwnServices() throws Exception {
        long annasService = createServiceId(anna);
        createServiceId(marco);

        assertThat(mvc.get().uri("/api/panel/services").header(HEADER, anna))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) annasService);
    }

    @Test
    void practitionerCannotDeactivatePackageOfAnother() throws Exception {
        long marcosService = createServiceId(marco);
        long marcosPackage = createPackageId(marco, marcosService);

        assertThat(mvc.post().uri("/api/panel/packages/" + marcosPackage + "/deactivate").header(HEADER, anna))
                .hasStatus(HttpStatus.NOT_FOUND);
        Boolean active = jdbc.queryForObject("select active from package_offer where id = ?", Boolean.class, marcosPackage);
        assertThat(active).isTrue();
    }

    @Test
    void practitionerCannotCreatePackageOnServiceOfAnother() throws Exception {
        long marcosService = createServiceId(marco);

        assertThat(createPackage(anna, marcosService, 5)).hasStatus(HttpStatus.NOT_FOUND);
    }

    // ***** RULES *****

    @Test
    void packageOnDeactivatedServiceAnswers409WithCode() throws Exception {
        // arrange
        long serviceId = createServiceId(anna);
        assertThat(mvc.post().uri("/api/panel/services/" + serviceId + "/deactivate").header(HEADER, anna))
                .hasStatusOk()
                .bodyJson().extractingPath("$.active").isEqualTo(false);

        // act
        MvcTestResult result = createPackage(anna, serviceId, 5);

        // assert
        assertThat(result).hasStatus(HttpStatus.CONFLICT).hasContentType("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SERVICE_NOT_ACTIVE");
    }

    @Test
    void errorResponsesExposeNoInternals() throws Exception {
        long marcosService = createServiceId(marco);

        MvcTestResult notFound = mvc.post().uri("/api/panel/services/" + marcosService + "/deactivate").header(HEADER, anna).exchange();
        MvcTestResult invalid = createService(anna, """
                {"name": "", "durationMinutes": 0, "priceCents": 0}
                """);

        for (MvcTestResult result : new MvcTestResult[] {notFound, invalid}) {
            String body = result.getResponse().getContentAsString().toLowerCase();
            assertThat(body).doesNotContain("exception", "trace", "select ", "insert ", "hibernate", "com.posturestudio");
        }
    }

    // ***** HELPERS *****

    private MvcTestResult createService(String practitioner, String json) {
        return mvc.post().uri("/api/panel/services").header(HEADER, practitioner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private long createServiceId(String practitioner) throws Exception {
        MvcTestResult result = createService(practitioner, """
                {"name": "Seduta posturale", "durationMinutes": 60, "priceCents": 3000}
                """);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MvcTestResult createPackage(String practitioner, long serviceId, int sessions) {
        return mvc.post().uri("/api/panel/packages").header(HEADER, practitioner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"serviceId": %d, "name": "Pacchetto", "sessionsCount": %d, "priceCents": 14000, "validityDays": 90}
                        """.formatted(serviceId, sessions))
                .exchange();
    }

    private long createPackageId(String practitioner, long serviceId) throws Exception {
        MvcTestResult result = createPackage(practitioner, serviceId, 5);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
