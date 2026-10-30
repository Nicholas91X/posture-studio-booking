package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

@DataJpaTest
@Import({TestcontainersConfiguration.class, AccountService.class})
public class AccountServiceTest {

    @Autowired
    AccountService accounts;

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
}
