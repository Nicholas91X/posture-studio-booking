package com.posturestudio.booking.auth;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

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

    // Email and state of these accounts, by user id (guide 26, part 7): one query whatever their number,
    // as activeAmong. A record, never the entity: outside auth nobody can change an account by mistake.
    // An id without an account is simply missing from the map
    @Transactional(readOnly = true)
    public Map<Long, AccountSummary> summariesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AccountSummary::from));
    }

    // A new practitioner account, for the admin (D-021, guide 26, part 4); its id. Joins the caller's
    // transaction: if the practitioner row then fails, the account is rolled back too.
    // Any account already on this email is refused, whatever its role: one role per user, nobody is promoted.
    // Two creations at the same moment: the unique constraint stops the second (409 DATA_CONFLICT)
    @Transactional
    public long createPractitionerAccount(String email) {
        Email normalized = new Email(email);
        if (users.existsByEmail(normalized)) {
            throw new BusinessRuleException(ErrorCode.EMAIL_ALREADY_IN_USE, "This email already has an account");
        }
        return users.save(new AppUser(normalized, Role.PRACTITIONER)).getId();
    }

    // Deactivating and reactivating are idempotent (guide 26, 6.1). The sessions already open end at their
    // next request (SessionAccounts reads the account every time), and no code lets it in (D-050)
    @Transactional
    public void deactivate(long userId) {
        find(userId).deactivate();
    }

    @Transactional
    public void activate(long userId) {
        find(userId).activate();
    }

    // The verified phone of an account (D-055), E.164, if any: for the WhatsApp of a payment link (guide 34, part 9)
    @Transactional(readOnly = true)
    public java.util.Optional<String> phoneOf(long userId) {
        return java.util.Optional.ofNullable(find(userId).getPhone());
    }

    // The email of an account, normalized (D-020): for the profile of the customer (guide 25, 7.3)
    @Transactional(readOnly = true)
    public String emailOf(long userId) {
        return find(userId).getEmail().value();
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

    private AppUser find(long userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
    }
}
