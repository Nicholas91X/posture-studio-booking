package com.posturestudio.booking.customer;

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
import com.posturestudio.booking.shared.NotFoundException;

// The lock of the customer row. That it really makes a second request wait is proved in
// BookingConcurrencyTest, with two threads; here, its contract
@DataJpaTest
@Import({TestcontainersConfiguration.class, CustomerService.class})
public class CustomerServiceTest {

    @Autowired
    CustomerService service;

    @Autowired
    JdbcTemplate jdbc;

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

    private long insertCustomer(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('CUSTOMER', ?) returning id", Long.class, email);
        return jdbc.queryForObject(
                "insert into customer (user_id, first_name, last_name) values (?, 'Test', 'Customer') returning id",
                Long.class, userId);
    }
}
