package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
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

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.payment.FakeStripeGateway;

// Stripe down during a request (guide 31, part 4): the whole request must roll back, booking included.
// NOT @Transactional: in a transactional test the request would join the test's transaction, and its
// rollback would only happen at the end of the test, where nobody can look. Here every request commits or
// rolls back for real, and what was committed is deleted after each test. Same context as the API tests
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
public class BookingCheckoutRollbackTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeStripeGateway stripe;

    private long anna;
    private long seduta;
    private long cliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        stripe.reset();
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("rollback-anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("rollback-cliente@example.com");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        stripe.reset();
        jdbc.update("delete from payment where booking_id in (select id from booking where practitioner_id = ?)", anna);
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where id = ?", cliente);
        jdbc.update("delete from app_user where email like 'rollback-%'");
    }

    @Test
    void whenStripeIsDownNothingIsCreatedAndTheSlotStaysFree() {
        stripe.goDown();

        MvcTestResult result = request();

        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        // the booking was inserted and flushed before the call to Stripe: the rollback took it away
        assertThat(jdbc.queryForObject("select count(*) from booking where practitioner_id = ?", Long.class, anna)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment where booking_id in (select id from booking where practitioner_id = ?)",
                Long.class, anna)).isZero();

        // Stripe is back: the same slot is free, and the request goes through
        stripe.reset();
        assertThat(request()).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select status from booking where practitioner_id = ?", String.class, anna))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("select status from payment where booking_id in (select id from booking where practitioner_id = ?)",
                String.class, anna)).isEqualTo("PENDING");
    }

    private MvcTestResult request() {
        return mvc.post().uri("/api/customer/bookings").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d, "startsAt": "2026-11-09T09:00:00+01:00"}
                        """.formatted(anna, seduta))
                .exchange();
    }
}
