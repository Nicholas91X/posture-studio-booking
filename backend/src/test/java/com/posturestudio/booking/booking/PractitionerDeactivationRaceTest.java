package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestSessions;
import com.posturestudio.booking.TestcontainersConfiguration;

// The race between a deactivation and a new booking (D-077, guide 45, part 4), in both orders, with real commits.
// One side is a transaction of this test that holds its lock and waits; the other side is the real API, which must
// WAIT for it, and then see what it committed:
// (a) the deactivation holds FOR UPDATE: the request waits on its FOR KEY SHARE, then finds the practitioner
//     deactivated (404), and no session is born;
// (b) a booking holds FOR KEY SHARE with its row inserted: the deactivation waits on its FOR UPDATE, then counts the
//     session (409).
// Friday 6 November 2026; the session is Monday 9 November, 9:00. What was committed is deleted after each test
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class PractitionerDeactivationRaceTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestSessions sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long admin;
    private long anna;
    private long annasUser;
    private long seduta;
    private long cliente;
    private ExecutorService pool;

    @BeforeEach
    void annaAndHerCustomer() {
        BookingTestData data = new BookingTestData(jdbc);
        admin = jdbc.queryForObject("insert into app_user (role, email) values ('ADMIN', 'race-admin@example.com') returning id",
                Long.class);
        anna = data.practitioner("race-anna@example.com");
        annasUser = jdbc.queryForObject("select user_id from practitioner where id = ?", Long.class, anna);
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("race-cliente@example.com");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        pool.shutdownNow();
        jdbc.update("delete from payment where booking_id in (select id from booking where practitioner_id = ?)", anna);
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where id = ?", cliente);
        jdbc.update("delete from app_user where email like 'race-%'");
    }

    // (a) the deactivation first
    @Test
    void aRequestDuringADeactivationWaitsAndThenFindsThePractitionerGone() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> deactivation = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            // what PractitionerDeactivation does, held open
            jdbc.queryForObject("select id from practitioner where id = ? for update", Long.class, anna);
            jdbc.update("update app_user set active = false where id = ?", annasUser);
            locked.countDown();
            await(release);
        }));
        await(locked);

        Future<Integer> request = pool.submit(this::request);

        // the request must be waiting on the lock, not done
        Thread.sleep(500);
        assertThat(request.isDone()).as("the request waits for the deactivation").isFalse();
        release.countDown();
        deactivation.get(10, TimeUnit.SECONDS);

        assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from booking where practitioner_id = ?", Long.class, anna)).isZero();
    }

    // (b) the booking first
    @Test
    void aDeactivationDuringABookingWaitsAndThenCountsTheSession() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> booking = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            // what a new booking does, held open: its lock first, then its row
            jdbc.queryForObject("select id from practitioner where id = ? for key share", Long.class, anna);
            jdbc.update("""
                    insert into booking (customer_id, practitioner_id, service_id, starts_at, ends_at, status, booked_by,
                                         paid_with, price_cents, free_cancellation_until)
                    values (?, ?, ?, ?, ?, 'CONFIRMED', 'PRACTITIONER', 'IN_STUDIO', 3000, ?)
                    """, cliente, anna, seduta, BookingTestData.at(MONDAY_9), BookingTestData.at(MONDAY_9).plusHours(1),
                    BookingTestData.noticeBefore(MONDAY_9));
            locked.countDown();
            await(release);
        }));
        await(locked);

        Future<Integer> deactivation = pool.submit(() -> mvc.post().uri("/api/admin/practitioners/" + anna + "/deactivate")
                .with(sessions.user(admin)).exchange().getResponse().getStatus());

        Thread.sleep(500);
        assertThat(deactivation.isDone()).as("the deactivation waits for the booking").isFalse();
        release.countDown();
        booking.get(10, TimeUnit.SECONDS);

        assertThat(deactivation.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, annasUser)).isTrue();
    }

    private int request() {
        return mvc.post().uri("/api/customer/bookings").with(sessions.customer(cliente))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"practitionerId": %d, "serviceId": %d, "startsAt": "2026-11-09T09:00:00+01:00"}
                        """.formatted(anna, seduta))
                .exchange().getResponse().getStatus();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
