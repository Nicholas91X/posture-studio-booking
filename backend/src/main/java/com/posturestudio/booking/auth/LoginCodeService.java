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
import org.springframework.beans.factory.ObjectProvider;
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
 * Access codes (D-014): by email (guide 24) and by SMS to a verified phone, and the codes that verify a
 * phone (guide 29). One set of rules for all: 6 digits, 10 minutes, 5 attempts, only the HMAC stored, the
 * latest code of a destination and purpose is the only one that counts.
 *
 * Nobody must learn who is a customer of the studio (guide 24, 5.1): every accepted request writes one row,
 * whether the destination is known or not, so the limits behave the same; the email always leaves, and the
 * SMS leaves in another thread, so the answer never waits for the provider (guide 29, part 4).
 * An email never seen creates its account at the first right code; a phone never does.
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
    // optional (guide 29, 6.2): no provider configured, no SMS, and the SMS APIs answer 503
    private final ObjectProvider<SmsCodeSender> smsSender;
    private final LoginCodeProperties limits;
    private final Clock clock;
    private final TransactionTemplate transaction;
    // never java.util.Random: from a few outputs it can be predicted (guide 24, 3.5). Thread-safe
    private final SecureRandom random = new SecureRandom();

    LoginCodeService(LoginCodeRepository codes, AppUserRepository users, LoginCodeHasher hasher,
                     LoginCodeSender sender, ObjectProvider<SmsCodeSender> smsSender, LoginCodeProperties limits,
                     Clock clock, PlatformTransactionManager transactionManager) {
        this.codes = codes;
        this.users = users;
        this.hasher = hasher;
        this.sender = sender;
        this.smsSender = smsSender;
        this.limits = limits;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ***** EMAIL (guide 24) *****

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
        requireDestinationAndAddressLimits(LoginChannel.EMAIL, to.value(), from, now);

        // a deactivated account gets a row too, with a code nobody will ever know: same work, same limits
        String code = LoginCode.randomCode(random);
        codes.save(new LoginCode(LoginChannel.EMAIL, to.value(), hasher.hash(to.value(), code), from, now));
        boolean deactivated = users.findByEmail(to).map(user -> !user.isActive()).orElse(false);
        return new Issued(code, deactivated);
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
        Attempt attempt = attempt(codes.findFirstByChannelAndDestinationAndPurposeOrderByCreatedAtDescIdDesc(
                LoginChannel.EMAIL, to.value(), CodePurpose.SIGN_IN), to.value(), code, now);
        if (attempt != Attempt.RIGHT) {
            return attempt == Attempt.WRONG ? new Wrong() : new Expired();
        }
        Optional<AppUser> existing = users.findByEmail(to);
        if (existing.isEmpty()) {
            AppUser created = users.save(new AppUser(to, Role.CUSTOMER));
            return new Valid(created.getId(), Role.CUSTOMER, true);
        }
        return signedIn(existing.get());
    }

    // ***** SMS SIGN-IN (guide 29, part 4) *****

    // false: no SMS provider is configured (guide 29, 6.2). The controllers answer 503 SMS_UNAVAILABLE
    // before calling anything, the same for every number
    boolean smsAvailable() {
        return smsSender.getIfAvailable() != null;
    }

    /**
     * A sign-in code by SMS. The row is written for any number, the SMS leaves only for the verified number
     * of an active account (guide 29, 4.1): the limits behave the same, and the sender delivers in another
     * thread, so the answer takes the same time.
     *
     * @throws TooManyRequestsException beyond the limits (429), whether the number is known or not
     */
    void requestSms(String phone, InetAddress client) {
        SmsCodeSender sms = requireSms();
        PhoneNumber to = new PhoneNumber(phone);
        Optional<String> code = transaction.execute(status -> issueSms(to, client));
        code.ifPresent(value -> sms.sendCode(to, value, LoginCode.VALIDITY, CodePurpose.SIGN_IN));
    }

    private Optional<String> issueSms(PhoneNumber to, InetAddress client) {
        Instant now = clock.instant();
        InetAddress from = limitKeyOf(client);
        requireDestinationAndAddressLimits(LoginChannel.SMS, to.value(), from, now);
        // checked for every number, known or not: a cap that stopped only the known ones would tell them apart
        requireSmsCap(now);

        // never an account from a number, never an SMS to a deactivated one (guide 29, 4.4)
        boolean send = users.findByPhone(to.value()).map(AppUser::isActive).orElse(false);
        String code = LoginCode.randomCode(random);
        codes.save(new LoginCode(LoginChannel.SMS, CodePurpose.SIGN_IN, null, send, to.value(),
                hasher.hash(to.value(), code), from, now));
        return send ? Optional.of(code) : Optional.empty();
    }

    // As verify, with the latest SMS sign-in code of the number. A right code opens only the account that
    // has this verified number now; with none (a code nobody received, guessed) it opens nothing
    @Transactional
    public CodeCheck verifySms(String phone, String code) {
        PhoneNumber to = new PhoneNumber(phone);
        Instant now = clock.instant();
        Attempt attempt = attempt(codes.findFirstByChannelAndDestinationAndPurposeOrderByCreatedAtDescIdDesc(
                LoginChannel.SMS, to.value(), CodePurpose.SIGN_IN), to.value(), code, now);
        if (attempt != Attempt.RIGHT) {
            return attempt == Attempt.WRONG ? new Wrong() : new Expired();
        }
        return users.findByPhone(to.value()).map(this::signedIn).orElseGet(Expired::new);
    }

    // ***** PHONE VERIFICATION (guide 29, part 3) *****

    /**
     * A code to the number this signed-in account wants to add. Synchronous request, asynchronous SMS, as the
     * sign-in. The limits of the destination and the address, plus the verifications of this account and
     * the daily cap: the door SMS pumping would knock at (guide 29, 5.1).
     */
    void requestPhoneVerification(long userId, String phone, InetAddress client) {
        SmsCodeSender sms = requireSms();
        PhoneNumber to = new PhoneNumber(phone);
        String code = transaction.execute(status -> {
            Instant now = clock.instant();
            InetAddress from = limitKeyOf(client);
            requireDestinationAndAddressLimits(LoginChannel.SMS, to.value(), from, now);
            Limit perAccount = limits.sms().perAccount();
            requireBelow(codes.usageOfVerifications(userId, now.minus(perAccount.window())), perAccount, now);
            requireSmsCap(now);
            String value = LoginCode.randomCode(random);
            codes.save(new LoginCode(LoginChannel.SMS, CodePurpose.VERIFY_PHONE, userId, true, to.value(),
                    hasher.hash(to.value(), value), from, now));
            return value;
        });
        sms.sendCode(to, code, LoginCode.VALIDITY, CodePurpose.VERIFY_PHONE);
    }

    /**
     * The code this account asked for this number. Right: the number is this account's, also when it was
     * another's, because whoever has the phone now is its owner (numbers are recycled, guide 29, 3.4).
     */
    @Transactional
    public PhoneCheck confirmPhone(long userId, String phone, String code) {
        PhoneNumber number = new PhoneNumber(phone);
        Instant now = clock.instant();
        Attempt attempt = attempt(codes.findFirstByChannelAndDestinationAndPurposeAndRequestedByOrderByCreatedAtDescIdDesc(
                LoginChannel.SMS, number.value(), CodePurpose.VERIFY_PHONE, userId), number.value(), code, now);
        if (attempt != Attempt.RIGHT) {
            return attempt == Attempt.WRONG ? new PhoneCheck.Wrong() : new PhoneCheck.Expired();
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new IllegalStateException("No account " + userId));
        users.findByPhone(number.value())
                .filter(previous -> previous.getId() != userId)
                .ifPresent(previous -> {
                    previous.removePhone();
                    // Now, not at the commit. The unique constraint is checked at every statement, and at a
                    // flush Hibernate writes the updates in the order it loaded the entities (or by id, with
                    // hibernate.order_updates): this account first, so its update would find the number still
                    // taken. Never rely on that order: free the number with its own statement first
                    users.flush();
                });
        user.verifyPhone(number, now);
        return new PhoneCheck.Verified(number.value(), now);
    }

    // the number of this account, or an empty view
    @Transactional(readOnly = true)
    public AccountPhoneView phoneOf(long userId) {
        return users.findById(userId).map(AccountPhoneView::from).orElse(AccountPhoneView.NONE);
    }

    @Transactional
    public void removePhone(long userId) {
        users.findById(userId).ifPresent(AppUser::removePhone);
    }

    // ***** CLEANUP *****

    // Deletes the codes older than RETENTION: after that they serve nothing, and they hold IP addresses,
    // which are personal data (guide 24, 6.5). Returns how many
    @Transactional
    public int purgeOld() {
        return codes.deleteCreatedBefore(clock.instant().minus(LoginCodeProperties.RETENTION));
    }

    // ***** SHARED *****

    // The heart of every check (guide 24, part 4): the latest code, locked; used, expired or out of attempts
    // is EXPIRED; a wrong one counts the attempt, and the fifth wrong one is EXPIRED at once
    private Attempt attempt(Optional<LoginCode> latest, String destination, String code, Instant now) {
        if (latest.isEmpty() || !latest.get().usableAt(now)) {
            return Attempt.EXPIRED;
        }
        LoginCode loginCode = latest.get();
        if (!hasher.matches(loginCode.getCodeHash(), destination, String.valueOf(code))) {
            loginCode.failedAttempt();
            return loginCode.exhausted() ? Attempt.EXPIRED : Attempt.WRONG;
        }
        loginCode.use(now);
        return Attempt.RIGHT;
    }

    // no automatic reactivation (guide 24, 5.4): whoever deactivated the account had a reason
    private CodeCheck signedIn(AppUser user) {
        return user.isActive() ? new Valid(user.getId(), user.getRole(), false) : new AccountDeactivated();
    }

    private void requireDestinationAndAddressLimits(LoginChannel channel, String destination, InetAddress from, Instant now) {
        for (Limit limit : limits.perDestination()) {
            requireBelow(codes.usageOfDestination(channel, destination, now.minus(limit.window())), limit, now);
        }
        requireBelow(codes.usageOfAddress(from, now.minus(limits.perIp().window())), limits.perIp(), now);
    }

    private void requireSmsCap(Instant now) {
        Limit cap = limits.sms().sent();
        requireBelow(codes.usageOfSms(now.minus(cap.window())), cap, now);
    }

    // the controllers ask smsAvailable() first: getting here without a sender is a bug
    private SmsCodeSender requireSms() {
        SmsCodeSender sms = smsSender.getIfAvailable();
        if (sms == null) {
            throw new IllegalStateException("No SmsCodeSender: the caller must check smsAvailable() first");
        }
        return sms;
    }

    // Two requests at exactly the same time can both pass and exceed the limit by one: a brake, not a rule
    // about money, so no lock (unlike the booking limit, D-045). The SMS cap is a brake on money, but the
    // excess is at most one SMS per request in flight
    private static void requireBelow(WindowUsage usage, Limit limit, Instant now) {
        if (usage.count() >= limit.max()) {
            // when the oldest code of the window leaves it, there is room again
            Duration retryAfter = Duration.between(now, usage.oldest().plus(limit.window()));
            throw new TooManyRequestsException("Too many access codes requested", retryAfter);
        }
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

    private enum Attempt {
        RIGHT, WRONG, EXPIRED
    }

    // the plain code leaves the transaction only to be sent
    private record Issued(String code, boolean accountDeactivated) {
    }
}
