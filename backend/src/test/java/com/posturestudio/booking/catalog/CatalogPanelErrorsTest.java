package com.posturestudio.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

// Only the web layer (controller, ApiExceptionHandler, Jackson, validation): no database, no container.
// The service is a fake, so this test can produce the two errors the real API cannot be pushed into
@WebMvcTest(CatalogPanelController.class)
public class CatalogPanelErrorsTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    CatalogService catalog;

    @MockitoBean
    CurrentPractitioner current;

    @BeforeEach
    void someoneIsActing() {
        given(current.id()).willReturn(1L);
    }

    @Test
    void databaseConstraintAnswers409WithoutTheDatabaseMessage() throws Exception {
        // arrange: a message like the real one, full of internals
        given(catalog.createService(anyLong(), any())).willThrow(new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint "
                        + "\"service_pk\"] [insert into service (name) values (?)]"));

        // act
        MvcTestResult result = createService();

        // assert
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("DATA_CONFLICT");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("service_pk", "insert", "ERROR");
    }

    @Test
    void unexpectedErrorAnswers500WithoutDetails() throws Exception {
        given(catalog.createService(anyLong(), any())).willThrow(new IllegalStateException("secret internal detail"));

        MvcTestResult result = createService();

        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR).hasContentType("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret", "IllegalStateException");
    }

    private MvcTestResult createService() {
        return mvc.post().uri("/api/panel/services")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Seduta posturale", "durationMinutes": 60, "priceCents": 3000}
                        """)
                .exchange();
    }
}
