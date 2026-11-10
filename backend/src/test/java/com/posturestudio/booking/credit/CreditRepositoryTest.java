package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The entities of the credit module against the real schema of V15: what JPA writes is what the checks and the
// triggers accept. The other people and things (customer, practitioner, service, payment) are rows by SQL
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CreditRepositoryTest {

    // Tuesday 10 November 2026, 18:00 in Rome
    private static final Instant NOW = Instant.parse("2026-11-10T17:00:00Z");

    @Autowired
    CreditLotRepository lots;

    @Autowired
    CreditMovementRepository movements;

    @Autowired
    CreditLotExtensionRepository extensions;

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long anna;
    private long annasUser;
    private long seduta;
    private long customer;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        seduta = data.service(anna);
        customer = data.customer("cliente@example.com");
    }

    @Test
    void aPackageLotKeepsWhatWasBought() {
        long offer = data.packageOffer(anna, seduta);
        long payment = data.packagePayment(customer, offer);

        long id = lots.save(CreditLot.fromPackage(customer, anna, seduta, payment, offer, 12000,
                ValidUntil.daysAfter(NOW, 90), NOW)).getId();
        movements.save(CreditMovement.lotCreated(id, 5, NOW));
        entityManager.flush();
        entityManager.clear();

        CreditLot lot = lots.findById(id).orElseThrow();
        assertThat(lot.getSource()).isEqualTo(LotSource.PACKAGE);
        assertThat(lot.getPaymentId()).isEqualTo(payment);
        assertThat(lot.getPriceCents()).isEqualTo(12000);
        assertThat(lot.getValidUntil().lastDay()).isEqualTo(LocalDate.of(2027, 2, 8));
        assertThat(lot.getCreatedAt()).isEqualTo(NOW);
        assertThat(movements.balanceOf(id)).isEqualTo(5);
    }

    @Test
    void aGiftKeepsWhoGaveItAndWhy() {
        long id = lots.save(CreditLot.grant(customer, anna, seduta, annasUser, "  Seduta saltata per un nostro imprevisto ",
                new ValidUntil(LocalDate.of(2027, 1, 31)), NOW)).getId();
        entityManager.flush();
        entityManager.clear();

        CreditLot lot = lots.findById(id).orElseThrow();
        assertThat(lot.getSource()).isEqualTo(LotSource.GRANT);
        assertThat(lot.getGrantedBy()).isEqualTo(annasUser);
        assertThat(lot.getNote()).isEqualTo("Seduta saltata per un nostro imprevisto");
        assertThat(lot.getPaymentId()).isNull();
    }

    @Test
    void theBalanceIsTheSumOfTheMovements() {
        long id = gift();
        long booking = data.creditBooking(anna, seduta, customer);

        movements.save(CreditMovement.lotCreated(id, 5, NOW));
        movements.save(CreditMovement.booking(id, booking, NOW));
        movements.save(CreditMovement.bookingRelease(id, booking, NOW));
        movements.save(CreditMovement.adjustment(id, -2, annasUser, "Due sedute fatte prima del sistema", NOW));

        assertThat(movements.balanceOf(id)).isEqualTo(3);
        assertThat(movements.findByCreditLotIdOrderById(id)).extracting(CreditMovement::getReason).containsExactly(
                MovementReason.LOT_CREATED, MovementReason.BOOKING, MovementReason.BOOKING_RELEASE,
                MovementReason.ADJUSTMENT);
    }

    @Test
    void aLotWithoutMovementsHasNothing() {
        assertThat(movements.balanceOf(gift())).isZero();
    }

    // the database guards JPA too: the insert of a movement goes out at once (its id comes from the database)
    @Test
    void aDebitBelowZeroIsRefusedThroughJpaToo() {
        long id = gift();

        assertThatThrownBy(() -> movements.save(CreditMovement.adjustment(id, -1, annasUser, "Prova", NOW)))
                .hasStackTraceContaining("balance cannot go below zero");
    }

    // the order the trigger needs comes by itself: the extension row is written at save, the lot at the flush
    @Test
    void anExtensionMovesTheLastDayAndLeavesItsTrace() {
        long id = gift();
        CreditLot lot = lots.findById(id).orElseThrow();

        extensions.save(lot.extendTo(new ValidUntil(LocalDate.of(2027, 3, 8)), annasUser, "Il cliente era malato", NOW));
        entityManager.flush();

        assertThat(jdbc.queryForObject("select valid_until::text from credit_lot where id = ?", String.class, id))
                .isEqualTo("2027-03-08");
        CreditLotExtension trace = extensions.findByCreditLotIdOrderById(id).getFirst();
        assertThat(trace.getOldValidUntil()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(trace.getNewValidUntil()).isEqualTo(LocalDate.of(2027, 3, 8));
        assertThat(trace.getExtendedBy()).isEqualTo(annasUser);
        assertThat(trace.getCreatedAt()).isEqualTo(NOW);
    }

    // forgetting to save the trace is caught by the database at the flush
    @Test
    void withoutItsTraceTheExtensionIsRefused() {
        CreditLot lot = lots.findById(gift()).orElseThrow();

        lot.extendTo(new ValidUntil(LocalDate.of(2027, 3, 8)), annasUser, "Il cliente era malato", NOW);

        assertThatThrownBy(entityManager::flush).hasStackTraceContaining("only with its credit_lot_extension row");
    }

    @Test
    void anExtensionBackIsAProgrammingError() {
        CreditLot lot = lots.findById(gift()).orElseThrow();

        assertThatThrownBy(() -> lot.extendTo(new ValidUntil(LocalDate.of(2027, 1, 31)), annasUser, "Niente", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lot.getValidUntil().lastDay()).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    // a gift valid until Sunday 31 January 2027
    private long gift() {
        long id = lots.save(CreditLot.grant(customer, anna, seduta, annasUser, "Omaggio",
                new ValidUntil(LocalDate.of(2027, 1, 31)), NOW)).getId();
        entityManager.flush();
        return id;
    }
}
