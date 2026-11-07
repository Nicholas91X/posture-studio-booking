package com.posturestudio.booking.customer;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.shared.NotAuthenticatedException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * PROVISIONAL (guide 19, part 7, as D-028). TODO (step 8): remove this class and implement
 * CurrentCustomer from the authenticated user (customer.user_id). A "done" criterion of step 8.
 *
 * The identity is DECLARED by the client in a header, not verified: it must never reach production.
 */
@Component
class DevHeaderCurrentCustomer implements CurrentCustomer {

    static final String HEADER = "X-Customer-Id";

    // a proxy: each call reaches the request bound to the current thread
    private final HttpServletRequest request;
    private final CustomerRepository repository;

    DevHeaderCurrentCustomer(HttpServletRequest request, CustomerRepository repository) {
        this.request = request;
        this.repository = repository;
    }

    // no @Transactional needed: existsById of Spring Data is already transactional
    @Override
    public long id() {
        String value = request.getHeader(HEADER);
        try {
            long id = Long.parseLong(value);
            if (repository.existsById(id)) {
                return id;
            }
        } catch (NumberFormatException e) {
            // missing or not a number: same answer as an unknown id
        }
        throw new NotAuthenticatedException("No current customer");
    }
}
