package com.posturestudio.booking.notification;

import static com.posturestudio.booking.notification.PushEncryptionTest.AUTH;
import static com.posturestudio.booking.notification.PushEncryptionTest.BROWSER_PRIVATE;
import static com.posturestudio.booking.notification.PushEncryptionTest.BROWSER_PUBLIC;
import static com.posturestudio.booking.notification.PushEncryptionTest.bytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import com.posturestudio.booking.notification.FakePushService.Received;
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

    // the devices of Anna, from piece 5 (guide 28, part 11): a push service on localhost
    private static FakePushService pushService;

    @Autowired
    BookingService bookings;

    @Autowired
    RecordingMailSender mail;

    @Autowired
    com.posturestudio.booking.payment.FakeStripeGateway stripe;

    @Autowired
    MovableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long anna;
    private long maria;
    private long seduta;

    @BeforeAll
    static void startThePushService() throws Exception {
        pushService = new FakePushService();
    }

    @AfterAll
    static void stopThePushService() {
        pushService.close();
    }

    @BeforeEach
    void annaWorksAndMariaIsACustomer() {
        mail.clear();
        pushService.clear();
        stripe.reset();
        clock.reset();
        Long annaUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id", Long.class, ANNA);
        anna = jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Anna', 'Rossi') returning id",
                Long.class, annaUser);
        // the test payment account "alpha": Maria can pay Anna by card (guide 31)
        jdbc.update("insert into practitioner_payment_account (practitioner_id, account, assigned_at) values (?, 'alpha', now())",
                anna);
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
        jdbc.update("delete from push_subscription where practitioner_id = ?", anna);
        // the payments of step 9 point to the bookings, the payment account to the practitioner
        jdbc.update("delete from payment where booking_id in (select id from booking where practitioner_id = ?)", anna);
        jdbc.update("delete from booking where practitioner_id = ?", anna);
        jdbc.update("delete from practitioner_payment_account where practitioner_id = ?", anna);
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

        paid(booking);

        assertThat(awaitEmails(1)).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(MARIA);
            assertThat(email.subject()).startsWith("Prenotazione confermata");
        });
    }

    // AUTOMATIC, but the capture is refused for good (the hold released by hand on Stripe): the request goes back
    // to Anna as in manual mode, so she is told it waits for her answer, and Maria that it was sent with its
    // deadline (D-063, the check asked by Nicholas after piece 4)
    @Test
    void inAutomaticModeARefusedCaptureAsksAnnaToDecide() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        stripe.refuseCaptures();
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();

        paid(booking);

        await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(mail.sentTo(ANNA)).singleElement().extracting(Sent::subject)
                    .isEqualTo("Nuova richiesta da approvare: lunedì 9 novembre, 9:00");
            assertThat(mail.sentTo(MARIA)).singleElement().satisfies(email -> assertThat(email.text())
                    .contains("entro venerdì 6 novembre 2026, ore 22:00"));
        });
        assertThat(jdbc.queryForMap("select status, approval_deadline from booking where id = ?", booking))
                .containsEntry("status", "PENDING_APPROVAL")
                .hasEntrySatisfying("approval_deadline", deadline -> assertThat(((java.sql.Timestamp) deadline).toInstant())
                        .isEqualTo(java.time.Instant.parse("2026-11-06T21:00:00Z")));
    }

    // The criterion of the brief: a failed email undoes nothing. The booking stays where the service put it,
    // and the other email of the same event still leaves (guide 27, 7.1)
    @Test
    void aRefusedEmailUndoesNothingAndDoesNotStopTheOther() {
        mail.failNextTo(ANNA);
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();

        paid(booking);

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

    // ***** push (piece 5, guide 28) *****

    // D-032: a device took the push, so the email to Anna is not needed; Maria's email does not depend on it.
    // The push is decrypted as Anna's browser would: no customer, no treatment on a locked screen
    @Test
    void aDeviceThatTakesThePushReplacesTheEmailToAnna() {
        aDevice("phone");

        long booking = aPaidRequestWithPush();

        assertThat(mail.sentTo(ANNA)).isEmpty();
        assertThat(mail.sentTo(MARIA)).hasSize(1);
        Received push = pushService.received().getFirst();
        assertThat(push.headers().get("authorization")).startsWith("vapid t=");
        assertThat(push.headers().get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(push.headers().get("urgency")).isEqualTo("high");
        // until the deadline: 12 hours after the request of Friday 10:00
        assertThat(push.headers().get("ttl")).isEqualTo("43200");
        String payload = new String(FakeBrowser.decrypt(push.body(), bytes(BROWSER_PRIVATE), bytes(BROWSER_PUBLIC), bytes(AUTH)), UTF_8);
        assertThat(payload).isEqualTo("""
                {"title":"Nuova prenotazione da approvare",\
                "body":"lunedì 9 novembre, 9:00 · rispondi entro venerdì 6 novembre, 22:00",\
                "url":"http://localhost:3000","tag":"booking-%d"}""".formatted(booking));
    }

    // 410: the browser dropped the subscription. The row goes, and Anna gets the email instead
    @Test
    void aDeviceThatNoLongerExistsIsDeletedAndAnnaGetsTheEmail() {
        aDevice("old-phone");
        pushService.answer("old-phone", 410);

        aPaidRequest();

        assertThat(mail.sentTo(ANNA)).hasSize(1);
        assertThat(devicesOfAnna()).isZero();
    }

    // 500: the push service is down. The device stays (next time it may work), and the email goes
    @Test
    void aPushServiceDownKeepsTheDeviceAndAnnaGetsTheEmail() {
        aDevice("phone");
        pushService.answer("phone", 500);

        aPaidRequest();

        assertThat(mail.sentTo(ANNA)).hasSize(1);
        assertThat(devicesOfAnna()).isOne();
    }

    // one device reached is enough: no email, and the dead one is cleaned up on the way
    @Test
    void oneDeviceReachedOutOfTwoIsEnough() {
        aDevice("old-phone");
        aDevice("laptop");
        pushService.answer("old-phone", 410);

        aPaidRequestWithPush();

        assertThat(mail.sentTo(ANNA)).isEmpty();
        assertThat(pushService.received()).extracting(Received::device).containsExactlyInAnyOrder("old-phone", "laptop");
        assertThat(jdbc.queryForList("select endpoint from push_subscription where practitioner_id = ?", String.class, anna))
                .containsExactly(pushService.endpoint("laptop"));
    }

    private void aDevice(String name) {
        jdbc.update("insert into push_subscription (practitioner_id, endpoint, p256dh, auth) values (?, ?, ?, ?)",
                anna, pushService.endpoint(name), BROWSER_PUBLIC, AUTH);
    }

    // Maria asks and pays; returns once her email has arrived. The push and the decision about Anna's email
    // come before it in the same task (BookingNotifications), so by then they are done
    private long aPaidRequestWithPush() {
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();
        paid(booking);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(mail.sentTo(MARIA)).hasSize(1));
        return booking;
    }

    private long devicesOfAnna() {
        return jdbc.queryForObject("select count(*) from push_subscription where practitioner_id = ?", Long.class, anna);
    }

    // Maria asks and pays, in MANUAL mode; returns once the two emails of ApprovalRequested have arrived
    private long aPaidRequest() {
        long booking = bookings.request(maria, new NewBooking(anna, seduta, MONDAY_9)).id();
        paid(booking);
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

    // what the webhook does before asking the booking (guides 32 and 33): the payment held, with its PaymentIntent,
    // so that an approval has something to capture
    private void paid(long booking) {
        jdbc.update("update payment set status = 'AUTHORIZED', stripe_payment_intent_id = 'pi_test_' || booking_id where booking_id = ?",
                booking);
        bookings.paymentAuthorized(booking);
    }
}
