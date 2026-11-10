package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;

// Two transactions that really run at the same time (guide 18, 10.2; guide 19, 9.2).
// @DataJpaTest would run the test in ONE transaction, rolled back at the end: two threads cannot share it,
// and a row not yet committed is invisible to anyone else. So the test runs without a transaction
// (NOT_SUPPORTED), every thread commits its own, and @AfterEach deletes what was really written:
// the container is shared with the other tests of the same configuration.
// Every transaction stays open for a moment after its work (pg_sleep): this makes sure the two overlap.
// Without the pause the first could commit before the second even starts, and the test would prove
// nothing about simultaneous requests. Today is Friday 6 November 2026, 10:00 in Rome
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BookingConcurrencyTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";
    private static final String TUESDAY_9 = "2026-11-10T09:00+01:00";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    BookingService service;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long customerA;
    private long customerB;

    // outside a transaction every statement commits at once: the rows are visible to both threads
    @BeforeEach
    void insertPeople() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("concurrency-anna@example.com");
        seduta = data.service(anna, 60);
        customerA = data.customer("concurrency-a@example.com");
        customerB = data.customer("concurrency-b@example.com");
        data.weekdays(anna, "2026-11-02");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        // the payments of step 9 point to the bookings, the payment account to the practitioner
        jdbc.update("delete from payment where booking_id in (select id from booking where practitioner_id = ?)", anna);
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
        // the bands go with their version (on delete cascade, V3)
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where id in (?, ?)", customerA, customerB);
        jdbc.update("delete from app_user where email like 'concurrency-%'");
    }

    // ***** the database constraint alone (piece 1) *****

    @Test
    void twoSimultaneousInsertsOfTheSameSlotLeaveExactlyOne() throws Exception {
        Outcome outcome = runTogether(
                () -> inOpenTransaction(() -> data.booking(anna, seduta, customerA, MONDAY_9, MONDAY_10, "PENDING_PAYMENT")),
                () -> inOpenTransaction(() -> data.booking(anna, seduta, customerB, MONDAY_9, MONDAY_10, "PENDING_PAYMENT")));

        // One wins, the other is stopped, whatever the order. Two answers are both right (passi/07, "Errori", 5):
        // 23P01 if the first had committed when the second checked; 40P01 (deadlock) if both rows were in the
        // index and each waited for the other, until PostgreSQL killed one. Either way, one row
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.failures()).singleElement().satisfiesAnyOf(
                failure -> assertThat(failure).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("booking_no_overlap_ex"),
                failure -> assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class)
                        .hasMessageContaining("deadlock"));
        assertThat(bookingRows()).isEqualTo(1);
    }

    // ***** through BookingService (piece 2) *****

    @Test
    void twoSimultaneousRequestsOfOneCustomerDoNotExceedTheLimit() throws Exception {
        // limit 1, two different slots: the exclusion constraint cannot help, only the customer lock.
        // Without it both would count 0 and both would be booked
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);

        Outcome outcome = runTogether(
                () -> inOpenTransaction(() -> request(customerA, MONDAY_9)),
                () -> inOpenTransaction(() -> request(customerA, TUESDAY_9)));

        // the second waited for the first on the lock, then counted 1
        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.failures()).singleElement()
                .isInstanceOf(BusinessRuleException.class)
                .extracting(failure -> ((BusinessRuleException) failure).getCode())
                .isEqualTo(ErrorCode.BOOKING_LIMIT_REACHED);
        assertThat(bookingRows()).isEqualTo(1);
    }

    @Test
    void twoCustomersAskingTheSameSlotTogetherGetOneBookingAndAReadableError() throws Exception {
        // both checks see the slot free (neither sees the other's uncommitted row), so the second is stopped
        // by the constraint and BookingService translates it (guide 19, part 6). Should the second check
        // run after the first commit, the check itself answers: the same code, as the customer should see
        Outcome outcome = runTogether(
                () -> inOpenTransaction(() -> request(customerA, MONDAY_9)),
                () -> inOpenTransaction(() -> request(customerB, MONDAY_9)));

        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.failures()).singleElement()
                .isInstanceOf(BusinessRuleException.class)
                .extracting(failure -> ((BusinessRuleException) failure).getCode())
                .isEqualTo(ErrorCode.SLOT_NOT_AVAILABLE);
        assertThat(bookingRows()).isEqualTo(1);
    }

    // ***** helpers *****

    private record Outcome(int succeeded, List<Throwable> failures) {
    }

    // Two tasks on two threads, released together by a gate; waits for both and collects how they ended
    private static Outcome runTogether(Callable<?> first, Callable<?> second) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Future<?>> attempts = new ArrayList<>();
        for (Callable<?> task : List.of(first, second)) {
            attempts.add(threads.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        int succeeded = 0;
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Future<?> attempt : attempts) {
                try {
                    attempt.get(10, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    failures.add(e);
                }
            }
        } finally {
            threads.shutdownNow();
        }
        return new Outcome(succeeded, failures);
    }

    // The work in a transaction of its own, which then stays open for 0.3 seconds before the commit.
    // BookingService.request joins this transaction (REQUIRED): its lock and its row live until the commit
    private <T> T inOpenTransaction(Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                T result = work.call();
                jdbc.execute("select pg_sleep(0.3)");
                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private CustomerBookingView request(long customerId, String startsAt) {
        return service.request(customerId, new NewBooking(anna, seduta, OffsetDateTime.parse(startsAt)));
    }

    private Integer bookingRows() {
        return jdbc.queryForObject("select count(*) from booking where practitioner_id = ?", Integer.class, anna);
    }
}
