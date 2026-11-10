package com.posturestudio.booking.auth;

/**
 * What other modules may know of an account (guide 26, part 7): a snapshot to read, never the entity,
 * which would let them change it without the rules of auth. The email is the normalized one (D-020).
 */
public record AccountSummary(String email, boolean active) {

    static AccountSummary from(AppUser user) {
        return new AccountSummary(user.getEmail().value(), user.isActive());
    }
}
