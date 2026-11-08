package com.posturestudio.booking.customer;

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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.shared.NotFoundException;

// The lock of the customer row (that it really makes a second request wait is proved in
// BookingConcurrencyTest, with two threads), and finding or creating a customer by email (guide 22)
@DataJpaTest
@Import({TestcontainersConfiguration.class, CustomerService.class, AccountService.class})
public class CustomerServiceTest {

    @Autowired
    CustomerService service;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    // ***** lock *****

    @Test
    void anExistingCustomerIsLockedInsideATransaction() {
        long customer = insertCustomer("cliente@example.com");

        Assertions.assertDoesNotThrow(() -> service.lockForBooking(customer));
    }

    @Test
    void aMissingCustomerIsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.lockForBooking(999999));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void withoutATransactionTheLockIsRefused() {
        // MANDATORY: outside a transaction the lock would be released at once, protecting nothing.
        // Spring refuses before the method runs, so the customer does not even need to exist
        Assertions.assertThrows(IllegalTransactionStateException.class, () -> service.lockForBooking(1));
    }

    // ***** find or create *****

    @Test
    void aNewCustomerIsCreatedWithTheirNames() {
        long id = service.findOrCreate(new CustomerDetails("mario.rossi@example.com", "Mario", "Rossi"));
        entityManager.flush();

        Map<String, Object> row = jdbc.queryForMap("""
                select c.first_name, c.last_name, u.email, u.role
                from customer c join app_user u on u.id = c.user_id
                where c.id = ?
                """, id);
        assertThat(row).containsEntry("first_name", "Mario")
                .containsEntry("last_name", "Rossi")
                .containsEntry("email", "mario.rossi@example.com")
                .containsEntry("role", "CUSTOMER");
    }

    @Test
    void anExistingCustomerKeepsTheirOwnNames() {
        long existing = insertCustomer("mario.rossi@example.com");

        // the practitioner typed the name differently: the customer's data stay theirs (guide 22, 3.2)
        long id = service.findOrCreate(new CustomerDetails("Mario.Rossi@Example.com", "Marione", "R."));
        entityManager.flush();

        assertThat(id).isEqualTo(existing);
        assertThat(service.namesOf(List.of(id))).containsEntry(id, new CustomerName("Test", "Customer"));
    }

    @Test
    void namesOfNoCustomersIsEmpty() {
        assertThat(service.namesOf(List.of())).isEmpty();
    }

    private long insertCustomer(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }
}
