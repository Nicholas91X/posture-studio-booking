package com.posturestudio.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AdminBootstrap.Outcome;

// The first admin (guide 26, part 2). @DataJpaTest does not create AdminBootstrap (not a JPA component):
// each test builds its own, with the ADMIN_EMAIL of that case, as Spring would at startup
@DataJpaTest
@Import(TestcontainersConfiguration.class)
public class AdminBootstrapTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsTheAdminWhenMissingWithTheEmailNormalized() {
        assertThat(bootstrap("  Nicholas@Example.COM ").ensureAdmin()).isEqualTo(Outcome.CREATED);

        Map<String, Object> row = jdbc.queryForMap("select email, role, active from app_user");
        assertThat(row).containsEntry("email", "nicholas@example.com")
                .containsEntry("role", "ADMIN")
                .containsEntry("active", true);
    }

    @Test
    void theNextStartupFindsItAndCreatesNothing() {
        bootstrap("nicholas@example.com").ensureAdmin();

        assertThat(bootstrap("nicholas@example.com").ensureAdmin()).isEqualTo(Outcome.ALREADY_THERE);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void aDeactivatedAdminIsLeftAsItIs() {
        jdbc.update("insert into app_user (role, email, active) values ('ADMIN', 'nicholas@example.com', false)");

        assertThat(bootstrap("nicholas@example.com").ensureAdmin()).isEqualTo(Outcome.DEACTIVATED);
        // only an explicit act reactivates (D-050)
        assertThat(jdbc.queryForObject("select active from app_user", Boolean.class)).isFalse();
    }

    @Test
    void theEmailOfACustomerStopsTheStartupAndIsNotPromoted() {
        jdbc.update("insert into app_user (role, email) values ('CUSTOMER', 'nicholas@example.com')");

        var exception = Assertions.assertThrows(IllegalStateException.class,
                () -> bootstrap("nicholas@example.com").ensureAdmin());

        assertThat(exception.getMessage()).contains("CUSTOMER");
        assertThat(jdbc.queryForObject("select role from app_user", String.class)).isEqualTo("CUSTOMER");
    }

    @Test
    void theEmailOfAPractitionerStopsTheStartupToo() {
        jdbc.update("insert into app_user (role, email) values ('PRACTITIONER', 'anna@example.com')");

        Assertions.assertThrows(IllegalStateException.class, () -> bootstrap("anna@example.com").ensureAdmin());
    }

    @Test
    void anInvalidEmailStopsTheStartup() {
        var exception = Assertions.assertThrows(IllegalStateException.class,
                () -> bootstrap("nicholas-at-example.com").ensureAdmin());

        assertThat(exception.getMessage()).contains("ADMIN_EMAIL");
        assertThat(countUsers()).isZero();
    }

    @Test
    void withoutTheVariableNothingHappens() {
        // the case of the tests and of an environment without an admin
        assertThat(bootstrap("").ensureAdmin()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(bootstrap("   ").ensureAdmin()).isEqualTo(Outcome.NOT_CONFIGURED);
        assertThat(countUsers()).isZero();
    }

    private AdminBootstrap bootstrap(String adminEmail) {
        return new AdminBootstrap(users, transactionManager, adminEmail);
    }

    private int countUsers() {
        return jdbc.queryForObject("select count(*) from app_user", Integer.class);
    }
}
