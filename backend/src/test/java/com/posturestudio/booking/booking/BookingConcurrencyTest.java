package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// Two bookings of the same slot in two transactions that really run at the same time (guide 18, 10.2).
// @DataJpaTest would run the test in ONE transaction, rolled back at the end: two threads cannot share it,
// and a row not yet committed is invisible to anyone else. So the test runs without a transaction
// (NOT_SUPPORTED), every thread commits its own, and @AfterEach deletes what was really written:
// the container is shared with the other tests of the same configuration
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BookingConcurrencyTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private BookingTestData data;
    private long anna;
    private long service;
    private long customerA;
    private long customerB;

    // outside a transaction every statement commits at once: the rows are visible to both threads
    @BeforeEach
    void insertPeople() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("concurrency-anna@example.com");
        service = data.service(anna, 60);
        customerA = data.customer("concurrency-a@example.com");
        customerB = data.customer("concurrency-b@example.com");
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where id in (?, ?)", customerA, customerB);
        jdbc.update("delete from app_user where email like 'concurrency-%'");
    }

    @Test
    void twoSimultaneousBookingsOfTheSameSlotLeaveExactlyOne() throws Exception {
        // arrange: two customers, the same slot, a gate that opens for both at once
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Future<Long>> attempts = new ArrayList<>();
        for (long customer : List.of(customerA, customerB)) {
            attempts.add(threads.submit(() -> {
                start.await();
                return book(customer);
            }));
        }

        // act
        start.countDown();
        int succeeded = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<Long> attempt : attempts) {
            try {
                attempt.get(10, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        threads.shutdown();

        // assert: one wins, the other is stopped by the exclusion constraint, whatever the order
        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).singleElement()
                .isInstanceOf(DataIntegrityViolationException.class)
                .extracting(Throwable::getMessage).asString().contains("booking_no_overlap_ex");
        Integer rows = jdbc.queryForObject("select count(*) from booking where practitioner_id = ?", Integer.class, anna);
        assertThat(rows).isEqualTo(1);
    }

    // One transaction: insert, then stay open for a moment before the commit. The pause makes sure the
    // two transactions overlap: the second insert finds the first row not yet committed, waits for it,
    // and fails when it commits (guide 18, 3.3). Without the pause the first could commit before the
    // second even starts, and the test would prove nothing about simultaneous requests
    private long book(long customer) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            long id = data.booking(anna, service, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT");
            jdbc.execute("select pg_sleep(0.3)");
            return id;
        });
    }
}
