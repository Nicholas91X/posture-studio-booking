package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The ledger only grows, also for SQL typed by hand (guide 36, parts 6 and 9; criterion of the brief "proved
// with direct SQL too"). The application connects as a superuser today, and so do these tests: permissions
// would not stop either of them, the triggers do. Every test runs in a transaction rolled back at the end
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class CreditLedgerImmutabilityTest {

    @Autowired
    JdbcTemplate jdbc;

    private CreditTestData data;
    private long annasUser;
    private long lot;
    private long created;

    @BeforeEach
    void rows() {
        data = new CreditTestData(jdbc);
        long anna = data.practitioner("anna@example.com");
        annasUser = data.userOf(anna);
        long customer = data.customer("cliente@example.com");
        lot = data.grantLot(customer, anna, data.service(anna));
        created = data.lotCreated(lot, 5);
    }

    // ***** credit_movement *****

    @Test
    void aMovementCannotBeChanged() {
        assertRefused("UPDATE on credit_movement",
                () -> jdbc.update("update credit_movement set amount = 50 where id = ?", created));
    }

    @Test
    void aMovementCannotBeDeleted() {
        assertRefused("DELETE on credit_movement", () -> jdbc.update("delete from credit_movement where id = ?", created));
    }

    // a truncate does not touch the rows one by one: the row trigger would not fire, its own trigger does
    @Test
    void theLedgerCannotBeEmptied() {
        assertRefused("TRUNCATE on credit_movement", () -> jdbc.execute("truncate credit_movement"));
    }

    // ***** credit_lot_extension *****

    @Test
    void anExtensionCannotBeChanged() {
        long extension = extend("2027-02-08", "2027-03-08");

        assertRefused("UPDATE on credit_lot_extension",
                () -> jdbc.update("update credit_lot_extension set note = 'Altro' where id = ?", extension));
    }

    @Test
    void anExtensionCannotBeDeleted() {
        long extension = extend("2027-02-08", "2027-03-08");

        assertRefused("DELETE on credit_lot_extension",
                () -> jdbc.update("delete from credit_lot_extension where id = ?", extension));
    }

    @Test
    void theExtensionsCannotBeEmptied() {
        assertRefused("TRUNCATE on credit_lot_extension", () -> jdbc.execute("truncate credit_lot_extension"));
    }

    // ***** credit_lot *****

    @Test
    void aLotCannotBeDeleted() {
        // a lot without movements, so that it is the trigger answering and not the foreign key of a movement
        long anna = data.practitioner("solo@example.com");
        long empty = data.grantLot(data.customer("vuoto@example.com"), anna, data.service(anna));

        assertRefused("DELETE on credit_lot", () -> jdbc.update("delete from credit_lot where id = ?", empty));
    }

    @Test
    void theLotsCannotBeEmptied() {
        assertRefused("TRUNCATE on credit_", () -> jdbc.execute("truncate credit_lot cascade"));
    }

    @Test
    void aLotChangesNothingButItsLastValidDay() {
        assertRefused("only valid_until can change",
                () -> jdbc.update("update credit_lot set note = 'Un altro motivo' where id = ?", lot));
    }

    @Test
    void aLotCannotMoveItsLastValidDayWithoutTheTrace() {
        assertRefused("only with its credit_lot_extension row",
                () -> jdbc.update("update credit_lot set valid_until = '2027-12-31' where id = ?", lot));
    }

    @Test
    void aLotMovesItsLastValidDayWithItsTrace() {
        extend("2027-02-08", "2027-03-08");

        int updated = jdbc.update("update credit_lot set valid_until = '2027-03-08' where id = ?", lot);

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject("select valid_until::text from credit_lot where id = ?", String.class, lot))
                .isEqualTo("2027-03-08");
    }

    @Test
    void theTraceMustBeOfThisChange() {
        // an extension to 8 March does not allow a move to 30 April
        extend("2027-02-08", "2027-03-08");

        assertRefused("only with its credit_lot_extension row",
                () -> jdbc.update("update credit_lot set valid_until = '2027-04-30' where id = ?", lot));
    }

    @Test
    void aLotCannotMoveItsLastValidDayBack() {
        // the forward-only check of the extension makes the trace impossible, so the move is impossible too
        assertRefused("only with its credit_lot_extension row",
                () -> jdbc.update("update credit_lot set valid_until = '2027-01-01' where id = ?", lot));
    }

    private long extend(String oldDay, String newDay) {
        return jdbc.queryForObject("""
                insert into credit_lot_extension (credit_lot_id, old_valid_until, new_valid_until, extended_by, note,
                                                  created_at)
                values (?, ?::date, ?::date, ?, 'Il cliente era malato', now())
                returning id
                """, Long.class, lot, oldDay, newDay, annasUser);
    }

    private static void assertRefused(String message, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(message);
    }
}
