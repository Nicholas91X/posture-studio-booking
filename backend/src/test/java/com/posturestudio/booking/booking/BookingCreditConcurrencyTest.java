package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.LedgerCleanup;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.credit.CreditService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;

// The last credit, asked twice at the same moment by the same customer (guide 38, part 6; the brief: "two
// simultaneous requests of the same customer do not use the same credit"). Two different slots, so the
// exclusion constraint cannot help: only the locks. Real commits, as BookingConcurrencyTest: the test runs
// without a transaction, every request in its own, kept open a moment (pg_sleep) so that the two overlap.
// What was committed is deleted after, the ledger with LedgerCleanup. Today is Friday 6 November 2026, 10:00
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class, CreditService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingCreditConcurrencyTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String TUESDAY_9 = "2026-11-10T09:00+01:00";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    BookingService service;

    @Autowired
    BookingTestDoubles.RecordingCardCheckout checkout;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long cliente;
    private long lot;

    @BeforeEach
    void oneCreditLeft() {
        checkout.reset();
        data = new BookingTestData(jdbc);
        anna = data.practitioner("credit-race-anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
        cliente = data.customer("credit-race-cliente@example.com");
        lot = data.creditLot(anna, seduta, cliente, "2026-12-31", 1);
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        LedgerCleanup.delete(transactionManager, jdbc, sql -> {
            sql.update("delete from credit_movement where credit_lot_id = ?", lot);
            sql.update("delete from credit_lot where id = ?", lot);
            sql.update("delete from booking where practitioner_id = ?", anna);
            sql.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
            sql.update("delete from working_schedule where practitioner_id = ?", anna);
            sql.update("delete from service where practitioner_id = ?", anna);
            sql.update("delete from practitioner where id = ?", anna);
            sql.update("delete from customer where id = ?", cliente);
            sql.update("delete from app_user where email like 'credit-race-%'");
        });
    }

    // the second request waits for the first on the lock of the customer, then finds the lot empty: it goes by
    // card. One credit, used once
    @Test
    void twoRequestsTogetherUseTheLastCreditOnce() throws Exception {
        List<Object> outcomes = together(() -> inOpenTransaction(() -> request(MONDAY_9)),
                () -> inOpenTransaction(() -> request(TUESDAY_9)));

        assertThat(outcomes).allMatch(CustomerBookingView.class::isInstance);
        assertThat(outcomes).extracting(outcome -> ((CustomerBookingView) outcome).paidWith())
                .containsExactlyInAnyOrder(PaidWith.CREDIT, PaidWith.CARD);
        assertThat(data.balance(lot)).isZero();
        assertThat(checkout.started).hasSize(1);
    }

    // the same race with no card account: the one without the credit is refused, readably
    @Test
    void withoutACardAccountTheSecondRequestIsRefused() throws Exception {
        checkout.withoutAccount.add(anna);

        List<Object> outcomes = together(() -> inOpenTransaction(() -> request(MONDAY_9)),
                () -> inOpenTransaction(() -> request(TUESDAY_9)));

        assertThat(outcomes).filteredOn(CustomerBookingView.class::isInstance).singleElement()
                .extracting(outcome -> ((CustomerBookingView) outcome).paidWith()).isEqualTo(PaidWith.CREDIT);
        assertThat(outcomes).filteredOn(Throwable.class::isInstance).singleElement()
                .isInstanceOfSatisfying(com.posturestudio.booking.shared.BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo(com.posturestudio.booking.shared.ErrorCode.CARD_PAYMENTS_UNAVAILABLE));
        assertThat(data.balance(lot)).isZero();
    }

    // ***** helpers *****

    private CustomerBookingView request(String startsAt) {
        return service.request(cliente, new NewBooking(anna, seduta, OffsetDateTime.parse(startsAt)));
    }

    // two tasks released together; what each returned, or the exception it ended with
    private static List<Object> together(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Future<?>> attempts = new ArrayList<>();
        try {
            for (Callable<?> task : List.of(first, second)) {
                attempts.add(threads.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<?> attempt : attempts) {
                try {
                    outcomes.add(attempt.get(15, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

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
}
