package com.posturestudio.booking.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Who is in this request? (guide 25, 4.2). Spring Security asks a SecurityContextRepository at every
 * request; this one replaces the standard one, which would serialize the whole Authentication into the
 * session and remember the role of the moment of the sign-in.
 *
 * Here the session holds only two values: the user id and the instant of the sign-in. At every request
 * the account is read again from the database (one query by primary key):
 * - active: an Authentication with the role it has NOW;
 * - deactivated or gone, or the session older than MAX_DURATION: the session is invalidated and the
 *   request is anonymous (401). So a deactivated account is out even with a session already open.
 *
 * The context is built lazily (DeferredSecurityContext): an API under /api/public that never asks who is
 * acting costs no query.
 */
@Component
class SessionAccounts implements SecurityContextRepository {

    // The absolute limit of a session (OWASP: absolute timeout), however active the user is (guide 25, 4.4).
    // Also the Max-Age of the cookie (SessionCookieConfiguration): the cookie ends with it
    static final Duration MAX_DURATION = Duration.ofDays(90);

    static final String USER_ID = "auth.userId";
    static final String SIGNED_IN_AT = "auth.signedInAt";

    private final AppUserRepository users;
    private final Clock clock;

    SessionAccounts(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /**
     * Opens the session of this user (guide 25, 3.4). Two things Spring would do in its own login filters,
     * done here by hand because the sign-in happens in a controller:
     * - a new session id (session fixation, guide 25, 5.3): an id known before the sign-in is worth nothing after;
     * - the explicit save: the user id goes into the session, or the next request would be anonymous.
     */
    void signIn(HttpServletRequest request, long userId) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_ID, userId);
        session.setAttribute(SIGNED_IN_AT, clock.instant());
    }

    // the session is destroyed (Spring Session deletes its rows and expires the cookie)
    void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new Deferred(() -> load(request));
    }

    // the old method of the interface (deprecated, still abstract in Spring Security 7)
    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return load(holder.getRequest());
    }

    // Nothing to save: the session is written only by signIn. Spring Security calls this at the end of
    // requests in which the context changed; the context is rebuilt from the database anyway
    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(USER_ID) != null;
    }

    private SecurityContext load(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return context;
        }
        // pattern matching for instanceof: a session without our two values is anonymous
        if (!(session.getAttribute(USER_ID) instanceof Long userId)
                || !(session.getAttribute(SIGNED_IN_AT) instanceof Instant signedInAt)) {
            return context;
        }
        if (!clock.instant().isBefore(signedInAt.plus(MAX_DURATION))) {
            session.invalidate();
            return context;
        }
        Optional<AppUser> account = users.findById(userId).filter(AppUser::isActive);
        if (account.isEmpty()) {
            // deactivated (or deleted) after the sign-in: out now, not at the end of the session
            session.invalidate();
            return context;
        }
        Role role = account.get().getRole();
        // ROLE_ in front: hasRole("PRACTITIONER") looks for the authority ROLE_PRACTITIONER
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SignedInUser(userId, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return context;
    }

    // Built at most once per request, when someone first asks. Spring's own SupplierDeferredSecurityContext
    // is not public, so a small one here
    private static final class Deferred implements DeferredSecurityContext {

        private final Supplier<SecurityContext> supplier;
        private SecurityContext context;

        Deferred(Supplier<SecurityContext> supplier) {
            this.supplier = supplier;
        }

        @Override
        public SecurityContext get() {
            if (context == null) {
                context = supplier.get();
            }
            return context;
        }

        // "generated" = nobody in the session: an empty context, made here and not read from anywhere
        @Override
        public boolean isGenerated() {
            return get().getAuthentication() == null;
        }
    }
}
