package com.posturestudio.booking.auth;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;

/**
 * The door of the auth module for the other modules (D-002): questions about user accounts,
 * asked by id. Other modules never read app_user (D-029).
 */
@Service
public class AccountService {

    private final AppUserRepository users;

    AccountService(AppUserRepository users) {
        this.users = users;
    }

    // A collection, not one id at a time: a caller with a list asks once (two queries in total),
    // not once per element, which would be an N+1 across modules that no join fetch can fix
    @Transactional(readOnly = true)
    public Set<Long> activeAmong(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            // "in ()" is not valid SQL: do not depend on how Hibernate translates an empty list
            return Set.of();
        }
        return users.findActiveIdsAmong(userIds);
    }

    // The customer account of this email, found or created (D-024, guide 22, part 3); its id.
    // The email is normalized by Email (D-020), so different cases are the same account.
    // A deactivated account comes back to life instead of a second one (email is unique).
    // An account with another role is refused: one role per user (D-021).
    // Joins the caller's transaction: if the booking then fails, the new account is rolled back too
    @Transactional
    public long customerAccountFor(String email) {
        Email normalized = new Email(email);
        AppUser user = users.findByEmail(normalized)
                .orElseGet(() -> users.save(new AppUser(normalized, Role.CUSTOMER)));
        if (user.getRole() != Role.CUSTOMER) {
            throw new BusinessRuleException(ErrorCode.EMAIL_NOT_A_CUSTOMER,
                    "The account of this email is not a customer account");
        }
        // idempotent: an active account stays active (managed entity, written at commit)
        user.activate();
        return user.getId();
    }
}
