package com.posturestudio.booking.practitioner;

import static com.posturestudio.booking.practitioner.BookingApproval.AUTOMATIC;
import static com.posturestudio.booking.practitioner.BookingApproval.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.NotFoundException;

// @DataJpaTest starts only JPA: services are not there, so PractitionerService is imported by hand,
// with the AccountService of auth it asks about active accounts
@DataJpaTest
@Import({TestcontainersConfiguration.class, PractitionerService.class, AccountService.class})
public class PractitionerServiceTest {

    @Autowired
    PractitionerService service;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    // the defaults of V1, V3 and V4
    private static final PractitionerSettings DEFAULTS = new PractitionerSettings(24, 90, 60, 24, 21, 4, MANUAL, 12);
    private static final PractitionerSettings CHANGED = new PractitionerSettings(48, 60, 30, 48, 14, 6, AUTOMATIC, 24);

    @Test
    void settingsOfNewPractitionerAreTheDatabaseDefaults() {
        // arrange: inserted with SQL, so the values come from the defaults of the migrations, not from Java
        long practitionerId = insertPractitioner("anna@example.com");

        // act
        PractitionerSettings settings = service.getSettings(practitionerId);

        // assert
        assertThat(settings).isEqualTo(DEFAULTS);
    }

    @Test
    void updatedSettingsAreWrittenWithoutSave() {
        // arrange
        long practitionerId = insertPractitioner("anna@example.com");

        // act
        PractitionerSettings returned = service.updateSettings(practitionerId, CHANGED);
        // the service joins the test transaction, so there is no commit at its end:
        // flush() does what the commit would do. Without it, the row would still have the old values
        entityManager.flush();

        // assert: read the row, not the object in memory
        assertThat(returned).isEqualTo(CHANGED);
        assertThat(readRow(practitionerId)).isEqualTo(CHANGED);
    }

    @Test
    void updateChangesOnlyThatPractitioner() {
        // arrange
        long anna = insertPractitioner("anna@example.com");
        long marco = insertPractitioner("marco@example.com");

        // act
        service.updateSettings(anna, CHANGED);
        entityManager.flush();

        // assert
        assertThat(readRow(marco)).isEqualTo(DEFAULTS);
    }

    @Test
    void getSettingsOfMissingPractitionerThrowsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.getSettings(999999));
    }

    @Test
    void updateSettingsOfMissingPractitionerThrowsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.updateSettings(999999, CHANGED));
    }

    // ***** PUBLIC *****

    @Test
    void activeInOrderHidesDeactivatedAccountsAndSortsByLastNameThenFirstName() {
        // arrange
        long rossiMarta = insertPractitioner("marta@example.com", "Marta", "Rossi", true);
        long bianchi = insertPractitioner("luca@example.com", "Luca", "Bianchi", true);
        long rossiAnna = insertPractitioner("anna@example.com", "Anna", "Rossi", true);
        insertPractitioner("marco@example.com", "Marco", "Verdi", false);

        // act
        List<Practitioner> listed = service.activeInOrder();

        // assert
        assertThat(listed).extracting(Practitioner::getId).containsExactly(bianchi, rossiAnna, rossiMarta);
        assertThat(listed.getFirst().getFirstName()).isEqualTo("Luca");
        assertThat(listed.getFirst().getLastName()).isEqualTo("Bianchi");
    }

    @Test
    void activeInOrderWithNoPractitionersIsEmpty() {
        assertThat(service.activeInOrder()).isEmpty();
    }

    @Test
    void requireActiveAcceptsAnActivePractitioner() {
        long anna = insertPractitioner("anna@example.com");

        Assertions.assertDoesNotThrow(() -> service.requireActive(anna));
    }

    @Test
    void deactivatedAndMissingPractitionerGiveTheSameNotFound() {
        // arrange
        long marco = insertPractitioner("marco@example.com", "Marco", "Verdi", false);
        long neverUsed = marco + 1000;

        // act
        var deactivated = Assertions.assertThrows(NotFoundException.class, () -> service.requireActive(marco));
        var missing = Assertions.assertThrows(NotFoundException.class, () -> service.requireActive(neverUsed));

        // assert: nothing in the message tells the two apart, apart from the id itself
        assertThat(deactivated.getMessage().replace(String.valueOf(marco), "ID"))
                .isEqualTo(missing.getMessage().replace(String.valueOf(neverUsed), "ID"));
    }

    // ***** ADMIN (guide 26) *****

    @Test
    void aCreatedPractitionerStartsWithTheDatabaseDefaultsInMemoryAndInTheRow() {
        AdminPractitionerView created = service.create(new NewPractitioner("anna@example.com", "Anna", "Rossi"));

        // in memory, same transaction: @Generated read the defaults back with the insert (guide 26, part 5)
        assertThat(service.getSettings(created.id())).isEqualTo(DEFAULTS);
        // in the row: the database wrote them, not Java
        assertThat(readRow(created.id())).isEqualTo(DEFAULTS);
        assertThat(created).isEqualTo(new AdminPractitionerView(created.id(), "anna@example.com", "Anna", "Rossi", true));
    }

    @Test
    void theSettingsOfACreatedPractitionerCanStillBeChanged() {
        // @Generated must not keep the columns out of the updates too
        long id = service.create(new NewPractitioner("anna@example.com", "Anna", "Rossi")).id();

        service.updateSettings(id, CHANGED);
        entityManager.flush();

        assertThat(readRow(id)).isEqualTo(CHANGED);
    }

    @Test
    void aRefusedEmailCreatesNoPractitioner() {
        insertPractitioner("anna@example.com");

        Assertions.assertThrows(BusinessRuleException.class,
                () -> service.create(new NewPractitioner("anna@example.com", "Anna", "Rossi")));
        assertThat(jdbc.queryForObject("select count(*) from practitioner", Integer.class)).isEqualTo(1);
    }

    @Test
    void deactivatedPractitionerLeavesThePublicListAndComesBack() {
        long anna = insertPractitioner("anna@example.com", "Anna", "Rossi", true);

        assertThat(service.deactivate(anna).active()).isFalse();
        assertThat(service.activeInOrder()).isEmpty();
        Assertions.assertThrows(NotFoundException.class, () -> service.requireActive(anna));

        assertThat(service.activate(anna).active()).isTrue();
        assertThat(service.activeInOrder()).extracting(Practitioner::getId).containsExactly(anna);
    }

    @Test
    void theAdminListHasEmailAndStateInNameOrder() {
        long rossi = insertPractitioner("anna@example.com", "Anna", "Rossi", true);
        long bianchi = insertPractitioner("marco@example.com", "Marco", "Bianchi", false);

        assertThat(service.listForAdmin()).containsExactly(
                new AdminPractitionerView(bianchi, "marco@example.com", "Marco", "Bianchi", false),
                new AdminPractitionerView(rossi, "anna@example.com", "Anna", "Rossi", true));
    }

    private PractitionerSettings readRow(long practitionerId) {
        Map<String, Object> row = jdbc.queryForMap(
                """
                select cancellation_notice_hours, single_credit_validity_days, slot_step_minutes,
                       min_booking_notice_hours, booking_horizon_days,
                       max_future_bookings, booking_approval, approval_window_hours
                from practitioner where id = ?
                """,
                practitionerId);
        return new PractitionerSettings(
                (Integer) row.get("cancellation_notice_hours"),
                (Integer) row.get("single_credit_validity_days"),
                (Integer) row.get("slot_step_minutes"),
                (Integer) row.get("min_booking_notice_hours"),
                (Integer) row.get("booking_horizon_days"),
                (Integer) row.get("max_future_bookings"),
                BookingApproval.valueOf((String) row.get("booking_approval")),
                (Integer) row.get("approval_window_hours"));
    }

    // ***** contact (guide 27, 5.4) *****

    // deactivated too: an email about a booking already made is still due
    @Test
    void theContactOfAPractitionerHasTheEmailOfTheirAccountEvenWhenDeactivated() {
        long anna = insertPractitioner("anna@example.com", "Anna", "Rossi", false);

        assertThat(service.contactOf(anna)).isEqualTo(new PractitionerContact("anna@example.com", "Anna", "Rossi"));
    }

    @Test
    void theContactOfAMissingPractitionerIsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.contactOf(999999));
    }

    private long insertPractitioner(String email) {
        return insertPractitioner(email, "Test", "Practitioner", true);
    }

    private long insertPractitioner(String email, String firstName, String lastName, boolean active) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('PRACTITIONER', ?, ?) returning id",
                Long.class, email, active);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, ?, ?) returning id",
                Long.class, userId, firstName, lastName);
    }
}
