package com.posturestudio.booking.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.posturestudio.booking.auth.CodeCheck.AccountDeactivated;
import com.posturestudio.booking.auth.CodeCheck.Expired;
import com.posturestudio.booking.auth.CodeCheck.Valid;
import com.posturestudio.booking.auth.CodeCheck.Wrong;
import com.posturestudio.booking.auth.LoginCodeProperties.Limit;
import com.posturestudio.booking.shared.DeliveryFailedException;
import com.posturestudio.booking.shared.TooManyRequestsException;

/**
 * Access codes by email (D-014, guide 24): request and verification.
 *
 * Nobody must learn who is a customer of the studio (guide 24, 5.1): every accepted request does the same
 * work whether the email is known, unknown or deactivated. It writes one row and sends one email, so the
 * answer and its duration are the same. The account is born at the first right code, not at the request.
 */
@Service
class LoginCodeService {

    private static final Logger log = LoggerFactory.getLogger(LoginCodeService.class);

    // the per-IP limit counts IPv6 networks of this size: one person usually has a whole /64 (guide 24, 6.4)
    private static final int IPV6_NETWORK_BYTES = 8;

    private final LoginCodeRepository codes;
    private final AppUserRepository users;
    private final LoginCodeHasher hasher;
    private final LoginCodeSender sender;
    private final LoginCodeProperties limits;
    private final Clock clock;
    private final TransactionTemplate transaction;
    // never java.util.Random: from a few outputs it can be predicted (guide 24, 3.5). Thread-safe
    private final SecureRandom random = new SecureRandom();

    LoginCodeService(LoginCodeRepository codes, AppUserRepository users, LoginCodeHasher hasher,
                     LoginCodeSender sender, LoginCodeProperties limits, Clock clock,
                     PlatformTransactionManager transactionManager) {
        this.codes = codes;
        this.users = users;
        this.hasher = hasher;
        this.sender = sender;
        this.limits = limits;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A new code for this email, requested from this address.
     * First the commit, then the email (guide 24, 7.5): an email sent before a failed commit would carry a
     * code that does not exist. So no @Transactional here: the transaction is only the part in the template.
     *
     * @throws TooManyRequestsException beyond the limits (429), whether the email has an account or not
     * @throws DeliveryFailedException if the email could not be sent (503)
     */
    void request(String email, InetAddress client) {
        Email to = new Email(email);
        Issued issued = transaction.execute(status -> issue(to, client));
        try {
            if (issued.accountDeactivated()) {
                sender.sendAccountDeactivated(to);
            } else {
                sender.sendCode(to, issued.code(), LoginCode.VALIDITY);
            }
        } catch (RuntimeException e) {
            // the row stays and counts in the limits: fine, the person asks again in a moment
            log.error("Could not send an access code email", e);
            throw new DeliveryFailedException("The access code could not be sent", e);
        }
    }

    // inside the transaction: limits, new row, and whether the account is deactivated
    private Issued issue(Email to, InetAddress client) {
        Instant now = clock.instant();
        InetAddress from = limitKeyOf(client);
        for (Limit limit : limits.perDestination()) {
            requireBelow(codes.usageOfDestination(LoginChannel.EMAIL, to.value(), now.minus(limit.window())), limit, now);
        }
        requireBelow(codes.usageOfAddress(from, now.minus(limits.perIp().window())), limits.perIp(), now);

        // a deactivated account gets a row too, with a code nobody will ever know: same work, same limits
        String code = LoginCode.randomCode(random);
        codes.save(new LoginCode(LoginChannel.EMAIL, to.value(), hasher.hash(to.value(), code), from, now));
        boolean deactivated = users.findByEmail(to).map(user -> !user.isActive()).orElse(false);
        return new Issued(code, deactivated);
    }

    // Two requests at exactly the same time can both pass and exceed the limit by one: a brake, not a rule
    // about money, so no lock (unlike the booking limit, D-045)
    private static void requireBelow(WindowUsage usage, Limit limit, Instant now) {
        if (usage.count() >= limit.max()) {
            // when the oldest code of the window leaves it, there is room again
            Duration retryAfter = Duration.between(now, usage.oldest().plus(limit.window()));
            throw new TooManyRequestsException("Too many access codes requested", retryAfter);
        }
    }

    /**
     * Checks a code against the latest one requested for this email (guide 24, part 4).
     * Never throws for a wrong code: the transaction must commit, or the attempt just counted would be
     * rolled back with it (guide 24, 4.1). The row is locked, so parallel guesses go one at a time.
     * A right code for an email never seen creates its CUSTOMER account (guide 24, 5.2): practitioners
     * and the admin exist only because someone created them.
     */
    @Transactional
    public CodeCheck verify(String email, String code) {
        Email to = new Email(email);
        Instant now = clock.instant();
        Optional<LoginCode> latest = codes.findFirstByChannelAndDestinationOrderByCreatedAtDescIdDesc(
                LoginChannel.EMAIL, to.value());
        if (latest.isEmpty() || !latest.get().usableAt(now)) {
            return new Expired();
        }
        LoginCode loginCode = latest.get();
        if (!hasher.matches(loginCode.getCodeHash(), to.value(), String.valueOf(code))) {
            loginCode.failedAttempt();
            // the fifth wrong code: tell at once that a new one is needed
            return loginCode.exhausted() ? new Expired() : new Wrong();
        }
        loginCode.use(now);

        Optional<AppUser> existing = users.findByEmail(to);
        if (existing.isEmpty()) {
            AppUser created = users.save(new AppUser(to, Role.CUSTOMER));
            return new Valid(created.getId(), true);
        }
        AppUser user = existing.get();
        // no automatic reactivation (guide 24, 5.4): whoever deactivated the account had a reason
        return user.isActive() ? new Valid(user.getId(), false) : new AccountDeactivated();
    }

    // Deletes the codes older than RETENTION: after that they serve nothing, and they hold IP addresses,
    // which are personal data (guide 24, 6.5). Returns how many
    @Transactional
    public int purgeOld() {
        return codes.deleteCreatedBefore(clock.instant().minus(LoginCodeProperties.RETENTION));
    }

    // What the per-IP limit counts (guide 24, 6.4): an IPv4 address as it is; an IPv6 address reduced to
    // its /64 network, host bits set to zero. Only this is stored: less personal data than the full address
    static InetAddress limitKeyOf(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            Arrays.fill(bytes, IPV6_NETWORK_BYTES, bytes.length, (byte) 0);
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException e) {
                // only for a wrong length, and 16 bytes is right
                throw new IllegalStateException(e);
            }
        }
        return address;
    }

    // the plain code leaves the transaction only to be sent
    private record Issued(String code, boolean accountDeactivated) {
    }
}
