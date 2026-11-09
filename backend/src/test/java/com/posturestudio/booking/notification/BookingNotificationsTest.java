package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.MovableClock;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.booking.BookingService;
import com.posturestudio.booking.booking.NewBooking;
import com.posturestudio.booking.booking.NewPanelBooking;
import com.posturestudio.booking.customer.CustomerDetails;
import com.posturestudio.booking.notification.RecordingMailSender.Sent;

// The booking emails end to end (guide 27, part 9): real commits, the real listener, the real executor,
// a MailSender that records instead of sending. Not @Transactional: a test transaction is rolled back,
// and after a rollback no listener runs at all. Rows deleted by hand in @AfterEach, as in BookingAfterCommitTest.
// The emails leave in another thread, after the service has returned: Awaitility waits for them, up to a
// limit, instead of a Thread.sleep that is either too short or too long.
// Jobs off: the expiry is called by the test, at the moment it chooses (MovableClock)
@SpringBootTest(properties = "booking.jobs.enabled=false")
@Import({TestcontainersConfiguration.class, MovableClock.Configuration.class, RecordingMailSender.Configuration.class})
class BookingNotificationsTest {

    private static final String ANNA = "notif-anna@example.com";
    private static final String MARIA = "notif-maria@example.com";
    // Monday 9 November, 9:00 in Rome: on the grid, past the notice of 24 hours from Friday 6 November, 10:00
    private static final OffsetDateTime MONDAY_9 = OffsetDateTime.parse("2026-11-09T09:00+01:00");
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Autowired
    BookingService bookings;

    @Autowired
    RecordingMailSender mail;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long anna;
    private long maria;
    private long seduta;

    @BeforeEach
    void annaWorksAndMariaIsACustomer() {
        mail.clear();
        clock.reset();
        Long annaUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, ANNA);
        anna = jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Anna', 'Rossi') returning id",
                Long.class, annaUser);
        Long mariaUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, MARIA);
        maria = jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Maria', 'Bianchi') returning id",
                Long.class, mariaUser);
        seduta = jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents) values (?, 'Seduta', 60, 5000) returning id",
                Long.class, anna);
        Long schedule = jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, '2026-11-02') returning id",
                Long.class, anna);
        for (int day = 1; day <= 5; day++) {
            jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, ?, '09:00', '13:00')",
                    schedule, day);
        }
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        mail.clear();
        clock.reset();
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from working_schedule where practitioner_id = ?", anna);
        jdbc.update("delete from service where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner where id = ?", anna);
        jdbc.update("delete from customer where user_id in (select id from app_user where email like 'notif-%')");
        jdbc.update("delete from app_user where email like 'notif-%'");
    }

    // MANUAL is the default of a new practitioner (D-030): paid, the request waits for Anna
    @Test
    void aPaidRequestWritesToAnnaAndToMaria() {
        aPaidRequest();

        assertThat(mail.sentTo(ANNA)).singleElement().extracting(Sent::subject)
                .isEqualTo("Nuova richiesta da approvare: lunedì 9 novembre, 9:00");
        assertThat(mail.sentTo(MARIA)).singleElement().satisfies(email -> {
            assertThat(email.subject()).isEqualTo("Richiesta di prenotazione inviata: lunedì 9 novembre, 9:00");
            // the deadline: 12 hours after the request, Friday 6 November 22:00 in Rome
            assertThat(email.text()).contains("Seduta con Anna Rossi", "entro venerdì 6 novembre 2026, ore 22:00");
            assertThat(email.from()).isEqualTo("no-reply@posture-studio.local");
        });
    }

    @Test
    void theApprovalWritesTheConfirmationToMaria() {
        long booking = aWaitingRequest();

        bookings.approve(anna, booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.subject()).isEqualTo("Prenotazione confermata: lunedì 9 novembre, 9:00");
        });
    }

    @Test
    void theRejectionWritesToMaria() {
        long booking = aWaitingRequest();

        bookings.reject(anna, booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.text()).contains("non è stata accettata");
        });
    }

    @Test
    void theWithdrawalWritesToMaria() {
        long booking = aWaitingRequest();

        bookings.withdraw(maria, booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.subject()).startsWith("Richiesta ritirata");
        });
    }

    // what the job does every minute (guide 21), called here after moving the clock past the deadline
    @Test
    void theExpiryWritesToMaria() {
        long booking = aWaitingRequest();
        clock.advance(Duration.ofHours(13));

        bookings.expireApproval(booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.text()).contains("non ha ricevuto risposta in tempo");
        });
    }

    // the customer may never have opened the site: the confirmation tells them (guide 27, 2.1)
    @Test
    void aBookingFromThePanelWritesTheConfirmationToMaria() {
        bookings.bookFromPanel(anna, new NewPanelBooking(seduta, MONDAY_9,
                new CustomerDetails(MARIA, "Maria", "Bianchi")));

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.subject()).startsWith("Prenotazione confermata");
        });
    }

    // AUTOMATIC: confirmed at once, so only the confirmation, and nothing for Anna to approve
    @Test
    void inAutomaticModeMariaGetsOnlyTheConfirmation() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();

        bookings.paymentAuthorized(booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.subject()).startsWith("Prenotazione confermata");
        });
    }

    // The criterion of the brief: a failed email undoes nothing. The booking stays where the service put it,
    // and the other email of the same event still leaves (guide 27, 7.1)
    @Test
    void aRefusedEmailUndoesNothingAndDoesNotStopTheOther() {
        mail.failNextTo(ANNA);
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();

        bookings.paymentAuthorized(booking);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(mail.sentTo(MARIA)).hasSize(1));
        assertThat(mail.sentTo(ANNA)).isEmpty();
        assertThat(statusOf(booking)).isEqualTo("PENDING_APPROVAL");
    }

    // @Async: not the thread of the caller, a virtual thread of the notification executor (guide 27, part 4)
    @Test
    void theEmailsLeaveFromVirtualThreadsOfTheirOwn() {
        aPaidRequest();

        assertThat(mail.sent()).hasSize(2).allSatisfy(email -> {
            assertThat(email.thread()).startsWith("notification-").isNotEqualTo(Thread.currentThread().getName());
            assertThat(email.virtual()).isTrue();
        });
    }

    // @Async does not change the rule of @TransactionalEventListener: the approval runs inside an outer
    // transaction that is then rolled back, so its event is dropped and nothing is ever handed to the executor
    @Test
    void aRolledBackApprovalSendsNothing() {
        long booking = aWaitingRequest();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bookings.approve(anna, booking);
            status.setRollbackOnly();
        });

        // a negative in another thread: wait a while, and the list must stay empty all along
        await().during(Duration.ofMillis(500)).atMost(PATIENCE).until(() -> mail.sent().isEmpty());
        assertThat(statusOf(booking)).isEqualTo("PENDING_APPROVAL");
    }

    // Maria asks and pays, in MANUAL mode; returns once the two emails of ApprovalRequested have arrived
    private long aPaidRequest() {
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();
        bookings.paymentAuthorized(booking);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(mail.sent()).hasSize(2));
        return booking;
    }

    // the same, then those two emails are forgotten: the test sees only the emails of what it does next
    private long aWaitingRequest() {
        long booking = aPaidRequest();
        mail.clear();
        return booking;
    }

    private List<Sent> awaitEmails(int expected) {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(mail.sent()).hasSize(expected));
        return mail.sent();
    }

    private String statusOf(long booking) {
        return jdbc.queryForObject("select status from booking where id = ?", String.class, booking);
    }
}
