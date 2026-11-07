package com.posturestudio.booking.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.NotFoundException;

/**
 * The only door of the customer module (D-002). Depends on nobody but shared.
 */
@Service
public class CustomerService {

    private final CustomerRepository repository;

    CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /**
     * Locks the row of the customer until the caller's transaction ends: another transaction asking
     * the same lock for the same customer waits. booking uses it to count the future bookings of a
     * customer without a race between two requests of theirs (guide 19, part 4).
     *
     * MANDATORY: only inside a transaction that already exists. Without one, the lock would be released
     * as soon as the select ends, and this method would protect nothing without saying so.
     *
     * @throws NotFoundException if the customer does not exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForBooking(long customerId) {
        repository.findLockedById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
    }
}
