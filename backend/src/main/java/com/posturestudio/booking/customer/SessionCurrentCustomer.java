package com.posturestudio.booking.customer;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.auth.CurrentUser;
import com.posturestudio.booking.auth.Role;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotAuthenticatedException;

/**
 * The customer of the signed-in user (guide 25, 7.2): replaces DevHeaderCurrentCustomer (D-045).
 * A CUSTOMER account with no customer row has signed in but not given first and last name yet (D-050):
 * 409 PROFILE_INCOMPLETE, and the frontend shows the form of PUT /api/customer/profile.
 */
@Component
class SessionCurrentCustomer implements CurrentCustomer {

    private final CurrentUser user;
    private final CustomerRepository repository;

    SessionCurrentCustomer(CurrentUser user, CustomerRepository repository) {
        this.user = user;
        this.repository = repository;
    }

    @Override
    public long id() {
        if (user.role() != Role.CUSTOMER) {
            throw new NotAuthenticatedException("No current customer");
        }
        return repository.findByUserId(user.id())
                .map(Customer::getId)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.PROFILE_INCOMPLETE,
                        "First and last name are missing"));
    }
}
