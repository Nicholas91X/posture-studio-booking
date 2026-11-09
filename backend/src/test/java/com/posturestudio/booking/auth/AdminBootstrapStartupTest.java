package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.TestcontainersConfiguration;

// The wiring of AdminBootstrap (guide 26, 2.6): with ADMIN_EMAIL set, a real startup runs it after Flyway and
// the admin is there before the first request. Its own Spring context because of the property, so its own
// PostgreSQL container (TestcontainersConfiguration): the committed admin row reaches no other test
@SpringBootTest(properties = "auth.admin-email= Nicholas@Example.COM ")
@Import(TestcontainersConfiguration.class)
public class AdminBootstrapStartupTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theStartupCreatesTheAdminOfTheConfiguredEmail() {
        Map<String, Object> row = jdbc.queryForMap("select email, role, active from app_user");

        assertThat(row).containsEntry("email", "nicholas@example.com")
                .containsEntry("role", "ADMIN")
                .containsEntry("active", true);
    }
}
