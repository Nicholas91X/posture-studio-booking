package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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

import com.posturestudio.booking.LedgerCleanup;
import com.posturestudio.booking.TestcontainersConfiguration;

// The last credit of a lot, taken by two transactions at the same time (guide 36, 7.1): one gets it, the other
// is refused by the database, with plain SQL and no Java rule in between.
// The test runs outside a transaction (NOT_SUPPORTED, as BookingConcurrencyTest): every thread commits its own,
// and @AfterEach deletes what was really written. Every transaction stays open for a moment after its insert
// (pg_sleep), so that the two really overlap.
// It is the proof of the lock in the trigger: with "for no key update" removed from credit_movement_keep_balance,
// both sums see 1, both debits pass, and the balance ends at -1 (tried, guide 36, part 15)
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditBalanceConcurrencyTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private CreditTestData data;
    private long anna;
    private long annasUser;
    private long customer;
    private long lot;

    // outside a transaction every statement commits at once: the rows are visible to both threads
    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("concurrency-credit-anna@example.com");
        annasUser = data.userOf(anna);
        customer = data.customer("concurrency-credit-cliente@example.com");
        lot = data.lotWith(customer, anna, data.service(anna), 1);
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        LedgerCleanup.delete(transactionManager, jdbc, sql -> {
            sql.update("delete from credit_movement where credit_lot_id = ?", lot);
            sql.update("delete from credit_lot where id = ?", lot);
            sql.update("delete from service where practitioner_id = ?", anna);
            sql.update("delete from practitioner where id = ?", anna);
            sql.update("delete from customer where id = ?", customer);
            sql.update("delete from app_user where email like 'concurrency-credit-%'");
        });
    }

    @Test
    void twoTransactionsCannotBothTakeTheLastCredit() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> takeTheLastCredit = () -> {
            start.await();
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    data.adjustment(lot, -1, annasUser);
                    jdbc.execute("select pg_sleep(0.5)");
                });
                return true;
            } catch (DataIntegrityViolationException e) {
                assertThat(e).hasMessageContaining("balance cannot go below zero");
                return false;
            }
        };

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = threads.submit(takeTheLastCredit);
            Future<Boolean> second = threads.submit(takeTheLastCredit);
            start.countDown();

            List<Boolean> taken = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(taken).containsExactlyInAnyOrder(true, false);
        } finally {
            threads.shutdownNow();
        }
        assertThat(data.balance(lot)).isZero();
    }
}
