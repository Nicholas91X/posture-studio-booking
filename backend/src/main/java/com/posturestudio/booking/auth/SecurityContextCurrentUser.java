package com.posturestudio.booking.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.shared.NotAuthenticatedException;

/**
 * CurrentUser from the SecurityContextHolder, which SessionAccounts fills at every request.
 * An anonymous request has an AnonymousAuthenticationToken, whose principal is not a SignedInUser.
 */
@Component
class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public long id() {
        return signedIn().id();
    }

    @Override
    public Role role() {
        return signedIn().role();
    }

    // pattern matching for instanceof (Java 16): the check and the cast in one step
    private static SignedInUser signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SignedInUser user) {
            return user;
        }
        throw new NotAuthenticatedException("Nobody is signed in");
    }
}
