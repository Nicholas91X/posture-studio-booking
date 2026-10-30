package com.posturestudio.booking.auth;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
