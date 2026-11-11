package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.LedgerCleanup;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.credit.CreditService;
import com.posturestudio.booking.credit.PackagePurchase;

// The email of a package purchased (guide 37, part 10), end to end: a real commit, the real listener after it,
// in its own thread, a MailSender that records. Not @Transactional: after a rollback no listener runs at all,
// which is exactly what the second test proves. Rows committed by SQL and deleted after, the ledger with
// LedgerCleanup
@SpringBootTest(properties = "booking.jobs.enabled=false")
@Import({TestcontainersConfiguration.class, RecordingMailSender.Configuration.class})
class CreditNotificationsTest {

    private static final String MARIA = "credit-notif-maria@example.com";
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Autowired
    CreditService credits;

    @Autowired
    RecordingMailSender mail;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private long anna;
    private long maria;
    private long service;
    private long offer;
    private long payment;

    @BeforeEach
    void mariaPaidAPackageOfAnna() {
        mail.clear();
        long annasUser = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'credit-notif-anna@example.com') returning id",
                Long.class);
        anna = jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Anna', 'Rossi') returning id",
                Long.class, annasUser);
        long mariasUser = jdbc.queryForObject("insert into app_user (role, email) values ('CUSTOMER', ?) returning id",
                Long.class, MARIA);
        maria = jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Maria', 'Bianchi') returning id",
                Long.class, mariasUser);
        service = jdbc.queryForObject("""
                insert into service (practitioner_id, name, duration_minutes, price_cents)
                values (?, 'Seduta posturale', 60, 3000) returning id
                """, Long.class, anna);
        offer = jdbc.queryForObject("""
                insert into package_offer (practitioner_id, service_id, name, sessions_count, price_cents, validity_days)
                values (?, ?, '5 sedute', 5, 12000, 90) returning id
                """, Long.class, anna, service);
        payment = jdbc.queryForObject("""
                insert into payment (purpose, package_offer_id, customer_id, package_credits, package_validity_days,
                                     account, amount_cents, status, stripe_checkout_session_id, checkout_url, created_at)
                values ('PACKAGE', ?, ?, 5, 90, 'alpha', 12000, 'SUCCEEDED', 'cs_credit_notif', 'https://checkout.stripe.test/x',
                        now())
                returning id
                """, Long.class, offer, maria);
    }

    @AfterEach
    void deleteWhatWasCommitted() {
        LedgerCleanup.delete(transactionManager, jdbc, sql -> {
            sql.update("delete from credit_movement where credit_lot_id in (select id from credit_lot where payment_id = ?)",
                    payment);
            sql.update("delete from credit_lot where payment_id = ?", payment);
            sql.update("delete from payment where id = ?", payment);
            sql.update("delete from package_offer where id = ?", offer);
            sql.update("delete from service where id = ?", service);
            sql.update("delete from practitioner where id = ?", anna);
            sql.update("delete from customer where id = ?", maria);
            sql.update("delete from app_user where email like 'credit-notif-%'");
        });
    }

    @Test
    void afterTheCommitTheCustomerGetsTheConfirmation() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> credits.lotFromPackage(purchase()));

        await().atMost(PATIENCE).until(() -> !mail.sentTo(MARIA).isEmpty());
        RecordingMailSender.Sent sent = mail.sentTo(MARIA).getFirst();
        assertThat(sent.subject()).isEqualTo("Acquisto confermato");
        // paid on Friday 6 November in Rome, 90 days
        assertThat(sent.text()).contains("5 sedute di Seduta posturale con Anna Rossi")
                .contains("fino a giovedì 4 febbraio 2027 compreso");
        // in a virtual thread of the notification executor, never in the thread that committed (D-053)
        assertThat(sent.virtual()).isTrue();
        assertThat(mail.sent()).hasSize(1);
    }

    // a webhook whose transaction fails announces nothing: no email for a lot that does not exist
    @Test
    void aRolledBackTransactionSendsNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            credits.lotFromPackage(purchase());
            status.setRollbackOnly();
        });

        await().during(Duration.ofMillis(800)).atMost(PATIENCE).until(() -> mail.sentTo(MARIA).isEmpty());
        assertThat(jdbc.queryForObject("select count(*) from credit_lot where payment_id = ?", Long.class, payment))
                .isZero();
    }

    private PackagePurchase purchase() {
        return new PackagePurchase(payment, maria, anna, service, offer, 12000, 5, 90,
                Instant.parse("2026-11-06T09:00:00Z"));
    }
}
