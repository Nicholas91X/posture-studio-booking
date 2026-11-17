package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;
import java.util.Map;

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

// The panel answers to requests, from the outside. Today is Friday 6 November 2026, 10:00 in Rome;
// the requests wait until tomorrow at 10:00 (BookingTestData.LATER). Same configuration as
// BookingCustomerApiTest: same context. The card is the real StripeCardHolds with the fake Stripe of every test
// context (D-063); the waiting requests come with their held payment (BookingTestData.heldPayment)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Transactional
public class BookingPanelApiTest {


    @Autowired
    MockMvcTester mvc;

    // signed-in requests: a real session and a CSRF token (guide 25, part 8)
    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private String anna;
    private String marco;
    private long waiting;

    @BeforeEach
    void aRequestWaitsForAnna() {
        data = new BookingTestData(jdbc);
        long annaId = data.practitioner("anna@example.com");
        anna = String.valueOf(annaId);
        marco = String.valueOf(data.practitioner("marco@example.com"));
        long seduta = data.service(annaId, 60);
        long cliente = data.customer("cliente@example.com");
        waiting = data.booking(annaId, seduta, cliente, "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "PENDING_APPROVAL");
    }

    @Test
    void anApprovalAnswers200WithTheConfirmedBooking() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"id": %d, "customerId": %d, "serviceId": %d,
                         "startsAt": "2026-11-09T09:00:00+01:00", "endsAt": "2026-11-09T10:00:00+01:00",
                         "status": "CONFIRMED", "paidWith": "CARD", "approvalDeadline": "2026-11-07T10:00:00+01:00"}
                        """.formatted(waiting, customerOf(waiting), serviceOf(waiting)));
    }

    @Test
    void aRejectionAnswers200() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/reject").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    void anotherPractitionersRequestIsNotFound() {
        // 404 and not 403: the answer must not reveal that the booking exists
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").with(sessions.practitioner(marco)))
                .hasStatus(HttpStatus.NOT_FOUND);
        String status = jdbc.queryForObject("select status from booking where id = ?", String.class, waiting);
        assertThat(status).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void answeringTwiceAnswers409WithTheCode() {
        mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").with(sessions.practitioner(anna)).exchange();

        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/reject").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("BOOKING_TRANSITION_NOT_ALLOWED");
    }

    @Test
    void anAnswerAfterTheDeadlineAnswers409WithTheCode() {
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:30+01:00' where id = ?", waiting);

        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("APPROVAL_DEADLINE_PASSED");
    }

    // ***** booking from the panel (guide 22) *****

    @Test
    void aPanelBookingAnswers201WithLocationAndCanBeReadBack() throws Exception {
        data.weekdays(Long.parseLong(anna), "2026-11-02");

        MvcTestResult result = bookFromPanel(anna, "mario.rossi@example.com");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        int id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result).hasHeader("Location", "/api/panel/bookings/" + id);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        // the practitioner sees at once how it is paid (guide 39, part 5)
        assertThat(result).bodyJson().extractingPath("$.paidWith").isEqualTo("IN_STUDIO");
        // nobody had this email: the panel asks to check it (D-088)
        assertThat(result).bodyJson().extractingPath("$.customerCreated").isEqualTo(true);
        assertThat(mvc.get().uri("/api/panel/bookings/" + id).with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.startsAt").isEqualTo("2026-11-10T09:00:00+01:00");
        // 404 and not 403 for someone else's booking
        assertThat(mvc.get().uri("/api/panel/bookings/" + id).with(sessions.practitioner(marco))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // the email of an existing customer: not created, and nothing else about them in the answer (D-066, D-088)
    @Test
    void anExistingCustomerIsNotCreated() throws Exception {
        data.weekdays(Long.parseLong(anna), "2026-11-02");

        MvcTestResult result = bookFromPanel(anna, "cliente@example.com");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.customerCreated").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.customerId").isEqualTo((int) customerOf(waiting));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("firstName", "lastName", "email");
    }

    @Test
    void anInvalidEmailAnswers400OnItsNestedField() {
        // mario@localhost passes jakarta's @Email but not our Email record: @ValidEmail keeps one rule
        MvcTestResult result = bookFromPanel(anna, "mario@localhost");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("customer.email");
        assertThat(result).bodyJson().extractingPath("$.errors[0].constraint").isEqualTo("ValidEmail");
    }

    @Test
    void aMissingCustomerAnswers400() {
        MvcTestResult result = mvc.post().uri("/api/panel/bookings").with(sessions.practitioner(anna))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"serviceId": %d, "startsAt": "2026-11-10T09:00:00+01:00"}
                        """.formatted(serviceOf(waiting)))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("customer");
    }

    // ***** lists (guide 23) *****

    @Test
    void theRoomFlagNeverRevealsTheOtherPractitionersCustomer() throws Exception {
        // Marco's evaluation for "Segreto Cliente" overlaps Anna's request on Monday 9:30-10:00
        long marcoId = Long.parseLong(marco);
        long marcosEvaluation = data.evaluation(marcoId);
        long segreto = data.customer("segreto@example.com", "Segreto", "Cliente");
        long marcosBooking = data.booking(marcoId, marcosEvaluation, segreto,
                "2026-11-09T09:30+01:00", "2026-11-09T10:00+01:00", "CONFIRMED");

        MvcTestResult result = mvc.get().uri("/api/panel/bookings?from=2026-11-09&to=2026-11-15").with(sessions.practitioner(anna)).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[0].evaluationOverlaps").isEqualTo(List.of(
                Map.of("start", "2026-11-09T09:30:00+01:00", "end", "2026-11-09T10:00:00+01:00")));
        // nothing about Marco's booking: not the customer's name, not the email, not the booking id
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Segreto", "segreto@example.com", "\"id\":" + marcosBooking + ",");
    }

    // ***** credit or studio, chosen by the practitioner (D-090) *****

    @Test
    void theCreditCheckSaysWhetherACreditWouldPayAndUntilWhen() {
        long cliente = customerOf(waiting);
        long annasService = serviceOf(waiting);
        data.creditLot(Long.parseLong(anna), annasService, cliente, "2026-12-31", 3);

        assertThat(creditCheck(anna, annasService, "Cliente@Example.com"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("{\"usableCredit\": true, \"validUntil\": \"2026-12-31\"}");
    }

    // an email nobody has: no credit, and nobody is created by asking
    @Test
    void theCreditCheckOfAnUnknownEmailCreatesNobody() {
        assertThat(creditCheck(anna, serviceOf(waiting), "nessuno@example.com"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("{\"usableCredit\": false, \"validUntil\": null}");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email = 'nessuno@example.com'", Long.class))
                .isZero();
    }

    // a credit with Marco is not a credit with Anna: Anna learns nothing about it (D-066)
    @Test
    void theCreditCheckIgnoresTheOtherPractitionersCredits() {
        long marcoId = Long.parseLong(marco);
        data.creditLot(marcoId, data.service(marcoId, 60), customerOf(waiting), "2026-12-31", 3);

        assertThat(creditCheck(anna, serviceOf(waiting), "cliente@example.com"))
                .bodyJson().extractingPath("$.usableCredit").isEqualTo(false);
    }

    @Test
    void theCreditCheckWithoutTheEmailAnswers400() {
        assertThat(mvc.get().uri("/api/panel/bookings/credit-check")
                .queryParam("serviceId", String.valueOf(serviceOf(waiting)))
                .queryParam("startsAt", "2026-11-10T09:00:00+01:00")
                .with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("email");
    }

    // the practitioner chooses the studio: the credit stays where it is
    @Test
    void inStudioKeepsTheCreditOfTheCustomer() throws Exception {
        data.weekdays(Long.parseLong(anna), "2026-11-02");
        long lot = data.creditLot(Long.parseLong(anna), serviceOf(waiting), customerOf(waiting), "2026-12-31", 3);

        MvcTestResult inStudio = bookFromPanel(anna, "cliente@example.com", "2026-11-10T09:00:00+01:00", true);
        assertThat(inStudio).hasStatus(HttpStatus.CREATED);
        assertThat(inStudio).bodyJson().extractingPath("$.paidWith").isEqualTo("IN_STUDIO");
        assertThat(data.balance(lot)).isEqualTo(3);

        // without the choice, as before: the credit pays (D-072)
        MvcTestResult withCredit = bookFromPanel(anna, "cliente@example.com", "2026-11-10T11:00:00+01:00", null);
        assertThat(withCredit).bodyJson().extractingPath("$.paidWith").isEqualTo("CREDIT");
        assertThat(data.balance(lot)).isEqualTo(2);
    }

    // ***** the room before booking (D-088) *****

    // the same flag as the lists, for a slot not booked yet: only the shared times, nothing about Marco's customer
    @Test
    void theRoomCheckShowsTheEvaluationOfTheOtherPractitioner() throws Exception {
        long marcoId = Long.parseLong(marco);
        long segreto = data.customer("segreto@example.com", "Segreto", "Cliente");
        long marcosBooking = data.booking(marcoId, data.evaluation(marcoId), segreto,
                "2026-11-10T09:30+01:00", "2026-11-10T10:00+01:00", "CONFIRMED");
        long annasBookings = countBookingsOf(anna);

        MvcTestResult result = roomCheck(anna, serviceOf(waiting), "2026-11-10T09:00:00+01:00");

        assertThat(result).hasStatusOk();
        // Anna's 60 minutes from 9:00 share 9:30-10:00 with the evaluation
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [{"start": "2026-11-10T09:30:00+01:00", "end": "2026-11-10T10:00:00+01:00"}]""");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Segreto", String.valueOf(marcosBooking));
        // a question, not a booking: nothing written
        assertThat(countBookingsOf(anna)).isEqualTo(annasBookings);
    }

    // a session of Marco that is not an evaluation, next to Anna's that is not one either: no flag
    @Test
    void theRoomCheckIgnoresTwoOrdinarySessions() {
        long marcoId = Long.parseLong(marco);
        data.booking(marcoId, data.service(marcoId, 60), data.customer("altro@example.com"),
                "2026-11-10T09:00+01:00", "2026-11-10T10:00+01:00", "CONFIRMED");

        assertThat(roomCheck(anna, serviceOf(waiting), "2026-11-10T09:00:00+01:00"))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("[]");
    }

    // Marco's service is not Anna's: 404, as everything of someone else
    @Test
    void theRoomCheckOfAnotherPractitionersServiceIsNotFound() {
        long marcosService = data.service(Long.parseLong(marco), 60);

        assertThat(roomCheck(anna, marcosService, "2026-11-10T09:00:00+01:00")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void theRoomCheckWithoutTheStartAnswers400() {
        assertThat(mvc.get().uri("/api/panel/bookings/room-overlaps?serviceId=" + serviceOf(waiting))
                .with(sessions.practitioner(anna))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theRequestsListShowsTheWaitingRequest() {
        assertThat(mvc.get().uri("/api/panel/bookings/requests").with(sessions.practitioner(anna)))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) waiting);
    }

    @Test
    void aPeriodEndingBeforeItStartsAnswers400OnTo() {
        assertThat(mvc.get().uri("/api/panel/bookings?from=2026-11-15&to=2026-11-09").with(sessions.practitioner(anna)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("to");
    }

    @Test
    void theCustomerListShowsTheirOwn() {
        assertThat(mvc.get().uri("/api/customer/bookings").with(sessions.customer(String.valueOf(customerOf(waiting)))))
                .hasStatusOk()
                .bodyJson().extractingPath("$[*].id").asArray().containsExactly((int) waiting);
    }

    @Test
    void withoutASessionTheAnswerIs401() {
        assertThat(mvc.post().uri("/api/panel/bookings/" + waiting + "/approve").with(csrf()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult bookFromPanel(String practitioner, String email) {
        return mvc.post().uri("/api/panel/bookings").with(sessions.practitioner(practitioner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"serviceId": %d, "startsAt": "2026-11-10T09:00:00+01:00",
                         "customer": {"email": "%s", "firstName": "Mario", "lastName": "Rossi"}}
                        """.formatted(serviceOf(waiting), email))
                .exchange();
    }

    private MvcTestResult creditCheck(String practitioner, long serviceId, String email) {
        return mvc.get().uri("/api/panel/bookings/credit-check")
                .queryParam("serviceId", String.valueOf(serviceId))
                .queryParam("startsAt", "2026-11-10T09:00:00+01:00")
                .queryParam("email", email)
                .with(sessions.practitioner(practitioner))
                .exchange();
    }

    private MvcTestResult bookFromPanel(String practitioner, String email, String startsAt, Boolean inStudio) {
        return mvc.post().uri("/api/panel/bookings").with(sessions.practitioner(practitioner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"serviceId": %d, "startsAt": "%s", "inStudio": %s,
                         "customer": {"email": "%s", "firstName": "Mario", "lastName": "Rossi"}}
                        """.formatted(serviceOf(waiting), startsAt, inStudio, email))
                .exchange();
    }

    private MvcTestResult roomCheck(String practitioner, long serviceId, String startsAt) {
        return mvc.get().uri("/api/panel/bookings/room-overlaps")
                .queryParam("serviceId", String.valueOf(serviceId))
                .queryParam("startsAt", startsAt)
                .with(sessions.practitioner(practitioner))
                .exchange();
    }

    private long countBookingsOf(String practitioner) {
        return jdbc.queryForObject("select count(*) from booking where practitioner_id = ?", Long.class,
                Long.parseLong(practitioner));
    }

    private long customerOf(long booking) {
        return jdbc.queryForObject("select customer_id from booking where id = ?", Long.class, booking);
    }

    private long serviceOf(long booking) {
        return jdbc.queryForObject("select service_id from booking where id = ?", Long.class, booking);
    }
}
