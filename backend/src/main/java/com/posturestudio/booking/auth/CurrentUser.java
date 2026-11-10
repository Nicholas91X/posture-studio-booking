package com.posturestudio.booking.auth;

/**
 * The user signed in for the current request, for the other modules (guide 25, 7.1). They never touch
 * Spring Security: they ask auth, as for everything about accounts (D-002). CurrentPractitioner and
 * CurrentCustomer are built on this.
 */
public interface CurrentUser {

    /**
     * @throws com.posturestudio.booking.shared.NotAuthenticatedException if nobody is signed in (401)
     */
    long id();

    /**
     * The role of the account now, read from the database at this request (guide 25, 4.2).
     *
     * @throws com.posturestudio.booking.shared.NotAuthenticatedException if nobody is signed in (401)
     */
    Role role();
}
