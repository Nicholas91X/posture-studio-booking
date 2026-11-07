package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingTestDoubles.CommittedEvents;
import com.posturestudio.booking.booking.BookingTestDoubles.RecordingCardHolds;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;

// Events reach a @TransactionalEventListener only after a successful commit (guide 20, part 4).
// That needs real commits, so the test runs without its own transaction (NOT_SUPPORTED): every call of
// the service is a transaction of its own, really committed or rolled back, and @AfterEach deletes what
// was written, as in BookingConcurrencyTest. Same context as the other booking tests
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class BookingAfterCommitTest {

    @Autowired
    BookingService service;

    @Autowired
    RecordingCardHolds cards;

    @Autowired
    CommittedEvents committed;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long anna;
    private long booking;

    @BeforeEach
    void aPaidRequestWaitsForAnna() {
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("aftercommit-anna@example.com");
        long seduta = data.service(anna, 60);
        long cliente = data.customer("aftercommit-cliente@example.com");
        data.weekdays(anna, "2026-11-02");
        booking = service.request(cliente, new NewBooking(anna, seduta, OffsetDateTime.parse("2026-11-09T09:00+01:00"))).id();
        service.paymentAuthorized(booking);
        // the ApprovalRequested of the arrangement is forgotten
        committed.received.clear();
        cards.reset();
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        cards.reset();
        committed.received.clear();
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where user_id in (select id from app_user where email like 'aftercommit-%')");
        jdbc.update("delete from app_user where email like 'aftercommit-%'");
    }

    @Test
    void aCommittedApprovalReachesTheListener() {
        service.approve(anna, booking);

        assertThat(committed.received).singleElement().isInstanceOf(BookingConfirmed.class);
    }

    @Test
    void anEventPublishedInATransactionThatRollsBackNeverArrives() {
        // the approval runs inside an outer transaction (REQUIRED joins it) and publishes BookingConfirmed;
        // then the outer transaction is rolled back, as if the commit had failed. An @EventListener would
        // have sent "confirmed" already; @TransactionalEventListener drops the event with the rollback
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.approve(anna, booking);
            status.setRollbackOnly();
        });

        assertThat(statusOfTheBooking()).isEqualTo("PENDING_APPROVAL");
        assertThat(committed.received).isEmpty();
    }

    @Test
    void aDeclinedCaptureLeavesTheRequestWaiting() {
        // the state machine changed the status in memory, then the capture failed: the exception rolls
        // the transaction back, and the event, published after the capture, is never even published
        cards.declineCaptures = true;

        Assertions.assertThrows(IllegalStateException.class, () -> service.approve(anna, booking));

        assertThat(statusOfTheBooking()).isEqualTo("PENDING_APPROVAL");
        assertThat(committed.received).isEmpty();
    }

    private String statusOfTheBooking() {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, booking);
    }
}
