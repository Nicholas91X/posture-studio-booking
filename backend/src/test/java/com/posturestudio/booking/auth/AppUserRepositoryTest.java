package com.posturestudio.booking.auth;

import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Assertions;
import org.springframework.dao.DataIntegrityViolationException;

import com.posturestudio.booking.TestcontainersConfiguration;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class AppUserRepositoryTest {

    @Autowired
    AppUserRepository repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    public static final Email email = new Email("john.doe@example.com");

    @Test
    void savedUserCanBeFoundByEmail() {
        // arrange + act
        repository.save(new AppUser(email, Role.CUSTOMER));

        repository.flush();
        entityManager.clear();

        // assert
        assertThat(repository.findByEmail(email)).isPresent();
    }

    @Test 
    void idAndCreatedAtAreGenerated() {
        // arrange + act
        AppUser user = repository.save(new AppUser(email, Role.CUSTOMER));

        // assert
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isNotNull();
    }

    @Test 
    void columnRoleIsStoredAsString() {
        // arrange + act
        AppUser user = repository.save(new AppUser(email, Role.CUSTOMER));

        repository.flush();
        entityManager.clear();

        String stored = jdbc.queryForObject(
                "select role from app_user where id = ?", String.class, user.getId());

        assertThat(stored).isEqualTo("CUSTOMER");
    }

    @Test
    void findByEmailWithEmailNeverSavedReturnsEmptyOptional() {
        // arrange + act
        var result = repository.findByEmail(new Email("example@email.com"));

        // assert
        assertThat(result).isEmpty();
    }

    @Test 
    void twoUsersWithSameEmailCannotBeSaved() {
        // arrange
        repository.save(new AppUser(email, Role.CUSTOMER));

        // act + assert
        var exception = Assertions.assertThrows(
            DataIntegrityViolationException.class,
            () -> repository.save(new AppUser(email, Role.PRACTITIONER))
        );
        assertThat(exception.getMessage()).contains("app_user_email_uk");
    }

    @Test 
    void directlySavingUserWithEmailWithCapitalLettersSavesEmailInLowerCase() {
        // arrange + act
        repository.save(new AppUser(new Email("JOHN.DOE@EXAMPLE.COM"), Role.CUSTOMER));

        repository.flush();
        entityManager.clear();

        // assert
        assertThat(repository.findByEmail(new Email("john.doe@example.com"))).isPresent();
    }
    
    @Test 
    void existsByEmailReturnsTrueForExistingEmail() {
        // arrange
        repository.save(new AppUser(email, Role.CUSTOMER));

        repository.flush();
        entityManager.clear();

        // act
        boolean exists = repository.existsByEmail(email);

        // assert
        assertThat(exists).isTrue();
    }

    @Test 
    void existsByEmailReturnsFalseForNonExistingEmail() {
        // act
        boolean exists = repository.existsByEmail(new Email("non.existent@example.com"));

        // assert
        assertThat(exists).isFalse();
    }

    @Test 
    void deactivateAndActivateMethodsChangeActiveStatus() {
        // arrange
        AppUser user = repository.save(new AppUser(email, Role.CUSTOMER));

        repository.flush();
        entityManager.clear();

        // act
        user.deactivate();
        repository.save(user);
        repository.flush();
        entityManager.clear();

        // assert
        assertThat(repository.findByEmail(email)).get().extracting(AppUser::isActive).isEqualTo(false);

        // act
        user.activate();
        repository.save(user);
        repository.flush();
        entityManager.clear();

        // assert
        assertThat(repository.findByEmail(email)).get().extracting(AppUser::isActive).isEqualTo(true);
    }

    @Test
    void findActiveIdsAmongReturnsOnlyActiveUsersAmongTheRequested() {
        // arrange: one active, one deactivated, one active but not asked for
        long active = repository.save(new AppUser(new Email("active@example.com"), Role.PRACTITIONER)).getId();
        AppUser deactivated = repository.save(new AppUser(new Email("off@example.com"), Role.PRACTITIONER));
        deactivated.deactivate();
        long notAsked = repository.save(new AppUser(new Email("other@example.com"), Role.PRACTITIONER)).getId();
        repository.flush();
        entityManager.clear();

        // act
        Set<Long> result = repository.findActiveIdsAmong(List.of(active, deactivated.getId(), 999999L));

        // assert
        assertThat(result).containsExactly(active);
        assertThat(result).doesNotContain(notAsked);
    }

    @Test
    void emailWithCapitalLettersInsertedWithSqlIsRejected() {
        // plain SQL skips the normalization of Email: this proves the database check, not Java
        var exception = Assertions.assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'John.Doe@Example.com')")
        );
        assertThat(exception.getMessage()).contains("app_user_email_ck");
    }

    @Test
    void unknownRoleInsertedWithSqlIsRejected() {
        var exception = Assertions.assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("insert into app_user (role, email) values ('GUEST', 'john.doe@example.com')")
        );
        assertThat(exception.getMessage()).contains("app_user_role_ck");
    }

}