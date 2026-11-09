package com.posturestudio.booking.auth;

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
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

@DataJpaTest
@Import({TestcontainersConfiguration.class, AccountService.class})
public class AccountServiceTest {

    @Autowired
    AccountService accounts;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void emptyCollectionGivesEmptySet() {
        assertThat(accounts.activeAmong(List.of())).isEmpty();
    }

    @Test
    void deactivatedAccountIsNotActive() {
        // arrange: inserted with SQL, as another module would find them
        Long active = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'anna@example.com') returning id", Long.class);
        Long deactivated = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('PRACTITIONER', 'marco@example.com', false) returning id",
                Long.class);

        // act + assert
        assertThat(accounts.activeAmong(List.of(active, deactivated))).containsExactly(active);
    }

    // ***** customerAccountFor (guide 22, part 3) *****

    @Test
    void aNewEmailBecomesAnActiveCustomerAccountInLowercase() {
        long id = accounts.customerAccountFor("  Mario.Rossi@Example.COM ");
        entityManager.flush();

        Map<String, Object> row = jdbc.queryForMap("select email, role, active from app_user where id = ?", id);
        assertThat(row).containsEntry("email", "mario.rossi@example.com")
                .containsEntry("role", "CUSTOMER")
                .containsEntry("active", true);
    }

    @Test
    void theSameEmailWrittenDifferentlyIsTheSameAccount() {
        long first = accounts.customerAccountFor("mario.rossi@example.com");

        assertThat(accounts.customerAccountFor("MARIO.ROSSI@example.com")).isEqualTo(first);
    }

    @Test
    void aDeactivatedCustomerComesBackToLife() {
        Long deactivated = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('CUSTOMER', 'mario@example.com', false) returning id",
                Long.class);

        long id = accounts.customerAccountFor("mario@example.com");
        entityManager.flush();

        assertThat(id).isEqualTo(deactivated);
        Boolean active = jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, id);
        assertThat(active).isTrue();
    }

    @Test
    void theEmailOfAPractitionerIsRefused() {
        // one role per user (D-021)
        jdbc.update("insert into app_user (role, email) values ('PRACTITIONER', 'anna@example.com')");

        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> accounts.customerAccountFor("anna@example.com"));
        assertThat(exception.getCode()).isEqualTo(ErrorCode.EMAIL_NOT_A_CUSTOMER);
    }

    // ***** for the admin (guide 26) *****

    @Test
    void aPractitionerAccountIsCreatedActiveAndInLowercase() {
        long id = accounts.createPractitionerAccount(" Anna.Rossi@Example.COM ");

        Map<String, Object> row = jdbc.queryForMap("select email, role, active from app_user where id = ?", id);
        assertThat(row).containsEntry("email", "anna.rossi@example.com")
                .containsEntry("role", "PRACTITIONER")
                .containsEntry("active", true);
    }

    @Test
    void anEmailWithAnAccountOfAnyRoleIsRefused() {
        // one role per user (D-021): nobody is promoted, a deactivated account is not reused
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'mario@example.com')");
        jdbc.update("insert into app_user (role, email, active) values ('PRACTITIONER', 'anna@example.com', false)");
        jdbc.update("insert into app_user (role, email) values ('ADMIN', 'admin@example.com')");

        for (String email : List.of("mario@example.com", "ANNA@example.com", "admin@example.com")) {
            var exception = Assertions.assertThrows(BusinessRuleException.class,
                    () -> accounts.createPractitionerAccount(email));
            assertThat(exception.getCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_IN_USE);
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isEqualTo(3);
    }

    @Test
    void summariesGiveEmailAndStateByIdAndSkipMissingIds() {
        Long anna = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'anna@example.com') returning id", Long.class);
        Long marco = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('PRACTITIONER', 'marco@example.com', false) returning id",
                Long.class);

        assertThat(accounts.summariesOf(List.of(anna, marco, -1L))).containsExactlyInAnyOrderEntriesOf(Map.of(
                anna, new AccountSummary("anna@example.com", true),
                marco, new AccountSummary("marco@example.com", false)));
        assertThat(accounts.summariesOf(List.of())).isEmpty();
    }

    @Test
    void deactivateAndActivateAreIdempotent() {
        Long anna = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', 'anna@example.com') returning id", Long.class);

        accounts.deactivate(anna);
        accounts.deactivate(anna);
        entityManager.flush();
        assertThat(jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, anna)).isFalse();

        accounts.activate(anna);
        accounts.activate(anna);
        entityManager.flush();
        assertThat(jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, anna)).isTrue();
    }

    @Test
    void deactivatingAMissingAccountIsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> accounts.deactivate(-1L));
    }
}
