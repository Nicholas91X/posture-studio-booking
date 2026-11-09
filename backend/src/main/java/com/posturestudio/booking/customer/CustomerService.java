package com.posturestudio.booking.customer;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * The only door of the customer module (D-002). Depends only on auth, for the accounts.
 */
@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final AccountService accounts;

    CustomerService(CustomerRepository repository, AccountService accounts) {
        this.repository = repository;
        this.accounts = accounts;
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

    // The customer of this email, found or created with these names (D-024, guide 22, part 3); its id.
    // An existing customer keeps their own names. Joins the caller's transaction: if the booking then
    // fails, account and customer created here are rolled back with it
    @Transactional
    public long findOrCreate(CustomerDetails details) {
        long userId = accounts.customerAccountFor(details.email());
        return repository.findByUserId(userId)
                .orElseGet(() -> repository.save(new Customer(userId, details.firstName(), details.lastName())))
                .getId();
    }

    // The names of a set of customers, in one query: for the panel lists of booking (piece 6)
    @Transactional(readOnly = true)
    public Map<Long, CustomerName> namesOf(Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByIdIn(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, CustomerName::from, (first, second) -> first));
    }
}
