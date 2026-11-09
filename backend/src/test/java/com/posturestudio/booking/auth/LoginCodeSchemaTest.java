package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The constraints of V5 with plain SQL: the database defends itself even from a bug in Java.
// That the context starts at all proves the inet <-> InetAddress mapping (ddl-auto=validate).
// One violation per test: after an error PostgreSQL refuses every statement of the same transaction
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class LoginCodeSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private void insert(String channel, int attempts, String validity) {
        jdbc.update("""
                insert into login_code (channel, destination, code_hash, requested_from, created_at, expires_at, attempts)
                values (?, 'mario@example.com', 'ab', '203.0.113.7', now(), now() + cast(? as interval), ?)""",
                channel, validity, attempts);
    }

    @Test
    void aValidRowIsAccepted() {
        insert("EMAIL", 5, "10 minutes");
    }

    @Test
    void onlyTheEmailChannelExistsToday() {
        // piece 6 adds SMS with a new migration (D-042)
        assertThatThrownBy(() -> insert("SMS", 0, "10 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptsNeverGoBeyondFive() {
        assertThatThrownBy(() -> insert("EMAIL", 6, "10 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aCodeExpiresAfterItWasCreated() {
        assertThatThrownBy(() -> insert("EMAIL", 0, "0 minutes")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
