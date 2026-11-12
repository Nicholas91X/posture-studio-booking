package com.posturestudio.booking.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.jayway.jsonpath.JsonPath;
import com.posturestudio.booking.TestcontainersConfiguration;

// The OpenAPI contract the frontend generates its types from (D-083): the three corrections of OpenApiConfiguration,
// read from the JSON the frontend reads. Turned on here only: off by default, as in production (PublicApiTest)
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    @Autowired
    MockMvcTester mvc;

    private String contract;

    @BeforeEach
    void readContract() throws Exception {
        var result = mvc.get().uri("/v3/api-docs").exchange();
        assertThat(result).hasStatusOk();
        contract = result.getResponse().getContentAsString();
    }

    // every code the backend may write has its place in the list, so the frontend's dictionary must cover it
    @Test
    void theErrorCodesAreAClosedListWithEveryCode() {
        List<String> codes = JsonPath.read(contract, "$.components.schemas.ApiProblem.properties.code.enum");

        assertThat(codes).containsAll(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        assertThat(codes).contains("CSRF_TOKEN_INVALID", "DATA_CONFLICT");
        // AuthErrorCode, from the bean of auth
        assertThat(codes).contains("CODE_WRONG", "CODE_EXPIRED", "ACCOUNT_DEACTIVATED", "SMS_UNAVAILABLE");
    }

    @Test
    void everyOperationDescribesItsErrorAnswers() {
        Map<String, Map<String, Object>> paths = JsonPath.read(contract, "$.paths");

        paths.forEach((path, operations) -> operations.keySet().forEach(method -> {
            String ref = JsonPath.read(contract, "$.paths['" + path + "']." + method
                    + ".responses['409'].content['application/problem+json'].schema.$ref");
            assertThat(ref).as(method + " " + path).isEqualTo("#/components/schemas/ApiProblem");
        }));
    }

    // an answer always has all its fields; the ones that may be null say so
    @Test
    void theFieldsOfAnAnswerAreRequiredAndNullOnlyWhereDeclared() {
        List<String> required = JsonPath.read(contract, "$.components.schemas.CustomerBookingView.required");
        Map<String, Object> paymentUrl = JsonPath.read(contract, "$.components.schemas.CustomerBookingView.properties.paymentUrl");
        Map<String, Object> startsAt = JsonPath.read(contract, "$.components.schemas.CustomerBookingView.properties.startsAt");

        assertThat(required).contains("id", "startsAt", "status", "paymentUrl", "freeCancellationUntil", "returnedCredit");
        assertThat(paymentUrl).containsEntry("nullable", true);
        assertThat(startsAt).doesNotContainKey("nullable");
        // a reference to another schema, nullable: allOf + nullable, since a $ref ignores what is next to it
        Map<String, Object> returnedCredit = JsonPath.read(contract,
                "$.components.schemas.CustomerBookingView.properties.returnedCredit");
        assertThat(returnedCredit).containsEntry("nullable", true);
        assertThat(JsonPath.<String>read(contract,
                "$.components.schemas.CustomerBookingView.properties.returnedCredit.allOf[0].$ref"))
                .isEqualTo("#/components/schemas/ReturnedCredit");
        List<String> practitioner = JsonPath.read(contract, "$.components.schemas.PublicPractitioner.required");
        assertThat(practitioner).contains("onlineBooking", "bookingApproval", "approvalWindowHours");
    }

    // a request body keeps what Bean Validation says, not "everything required"
    @Test
    void aRequestBodyKeepsItsValidation() {
        List<String> required = JsonPath.read(contract, "$.components.schemas.NewBooking.required");

        assertThat(required).containsExactlyInAnyOrder("practitionerId", "serviceId", "startsAt");
    }

    // ResponseEntity<?> has no type of its own: the sign-in declares its body
    @Test
    void theSignInDeclaresTheBodyOfItsAnswer() {
        String ref = JsonPath.read(contract,
                "$.paths['/api/public/auth/session'].post.responses['200'].content['application/json'].schema.$ref");

        assertThat(ref).isEqualTo("#/components/schemas/SessionView");
    }

    @Test
    void stripeWebhooksAreNotInTheContract() {
        Map<String, Object> paths = JsonPath.read(contract, "$.paths");

        assertThat(paths.keySet()).noneMatch(path -> path.startsWith("/api/webhooks"));
    }
}
