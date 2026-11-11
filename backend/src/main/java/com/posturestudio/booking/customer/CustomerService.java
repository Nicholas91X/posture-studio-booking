package com.posturestudio.booking.customer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
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

    // The profile of the customer of this account (guide 25, 7.3); 409 PROFILE_INCOMPLETE before the names
    @Transactional(readOnly = true)
    public CustomerProfile profileOf(long userId) {
        Customer customer = repository.findByUserId(userId)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.PROFILE_INCOMPLETE,
                        "First and last name are missing"));
        return new CustomerProfile(accounts.emailOf(userId), customer.getFirstName(), customer.getLastName());
    }

    // Creates the customer row of this account at the first sign-in (D-050), or corrects the names:
    // here the customer writes their own names, unlike the panel, which never overwrites them (D-048)
    @Transactional
    public CustomerProfile saveProfile(long userId, ProfileNames names) {
        Customer customer = repository.findByUserId(userId)
                .map(existing -> {
                    existing.rename(names.firstName(), names.lastName());
                    return existing;
                })
                .orElseGet(() -> repository.save(new Customer(userId, names.firstName(), names.lastName())));
        return new CustomerProfile(accounts.emailOf(userId), customer.getFirstName(), customer.getLastName());
    }

    // How to write to this customer (guide 27, 5.4): the email is the account's, asked to auth
    @Transactional(readOnly = true)
    public CustomerContact contactOf(long customerId) {
        Customer customer = repository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
        return new CustomerContact(accounts.emailOf(customer.getUserId()), customer.getFirstName(),
                customer.getLastName());
    }

    // The verified phone of this customer, if any (D-055): for the WhatsApp of a payment link (guide 34, part 9)
    @Transactional(readOnly = true)
    public java.util.Optional<String> phoneOf(long customerId) {
        Customer customer = repository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer " + customerId + " not found"));
        return accounts.phoneOf(customer.getUserId());
    }

    // Names and email of a set of customers, in two queries whatever their number (D-029): for "my customers" of
    // the panel (D-066, guide 41, part 4). The email is the account's, asked to auth
    @Transactional(readOnly = true)
    public Map<Long, CustomerContact> contactsOf(Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        List<Customer> found = repository.findByIdIn(customerIds);
        Map<Long, com.posturestudio.booking.auth.AccountSummary> accountsByUser =
                accounts.summariesOf(found.stream().map(Customer::getUserId).toList());
        return found.stream().collect(Collectors.toMap(Customer::getId, customer -> new CustomerContact(
                accountsByUser.get(customer.getUserId()).email(), customer.getFirstName(), customer.getLastName())));
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
