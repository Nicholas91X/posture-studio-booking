package com.posturestudio.booking.auth;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.junit.jupiter.api.Assertions;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest 
@Testcontainers 
public class AppUserRepositoryTest {

    @Container 
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

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
    
}