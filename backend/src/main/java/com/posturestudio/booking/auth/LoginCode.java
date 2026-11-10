package com.posturestudio.booking.auth;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

/**
 * One access code requested for a destination (D-014, guide 24). Only its HMAC is stored, never the code.
 * Only the latest code of a destination counts: asking for a new one makes the previous ones useless
 * without touching them (guide 24, 4.6).
 */
@Entity
@Table(name = "login_code")
class LoginCode {

    // The rules of D-014: product rules, not server settings. MAX_ATTEMPTS is repeated in
    // login_code_attempts_ck (V5)
    static final Duration VALIDITY = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    private static final int CODES = 1_000_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private LoginChannel channel;

    // sign in, or verify a phone (V8, guide 29, 3.2): the latest code is looked for per purpose
    @Enumerated(EnumType.STRING)
    private CodePurpose purpose;

    // VERIFY_PHONE only: the account that asked, the only one the code confirms the number for (guide 29, 3.3).
    // An id: AppUser is in this module, but the code does not need the account loaded
    private Long requestedBy;

    // false for an SMS sign-in to a number that is nobody's: a row so that the limits behave the same,
    // with no message behind it (guide 29, 4.3)
    private boolean messageSent;

    private String destination;

    private String codeHash;

    // PostgreSQL inet <-> java.net.InetAddress (Hibernate's own mapping; checked by ddl-auto=validate)
    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress requestedFrom;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant expiresAt;

    private int attempts;

    private Instant usedAt;

    protected LoginCode() {
        // JPA needs a no-arg constructor
    }

    // an email sign-in code: the only kind of the pieces 1 to 5
    LoginCode(LoginChannel channel, String destination, String codeHash, InetAddress requestedFrom, Instant now) {
        this(channel, CodePurpose.SIGN_IN, null, true, destination, codeHash, requestedFrom, now);
    }

    LoginCode(LoginChannel channel, CodePurpose purpose, Long requestedBy, boolean messageSent, String destination,
              String codeHash, InetAddress requestedFrom, Instant now) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        // the same rules as the checks of V8 on login_code, said earlier and with a clearer message
        if (purpose == CodePurpose.VERIFY_PHONE && channel != LoginChannel.SMS) {
            throw new IllegalArgumentException("A phone is verified by SMS only");
        }
        if ((purpose == CodePurpose.VERIFY_PHONE) != (requestedBy != null)) {
            throw new IllegalArgumentException("A verification code, and only it, has the account that asked");
        }
        if (channel == LoginChannel.EMAIL && !messageSent) {
            throw new IllegalArgumentException("An email always leaves");
        }
        this.requestedBy = requestedBy;
        this.messageSent = messageSent;
        this.destination = Objects.requireNonNull(destination, "destination must not be null");
        this.codeHash = Objects.requireNonNull(codeHash, "codeHash must not be null");
        this.requestedFrom = Objects.requireNonNull(requestedFrom, "requestedFrom must not be null");
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.expiresAt = now.plus(VALIDITY);
        this.attempts = 0;
    }

    // Six digits, zeros in front included: 000123 is a code like any other (guide 24, 3.5).
    // A RandomGenerator so that a test can pass a predictable one; the service passes a SecureRandom
    static String randomCode(RandomGenerator random) {
        return String.format("%06d", random.nextInt(CODES));
    }

    // not used, not expired, attempts left
    boolean usableAt(Instant now) {
        return usedAt == null && attempts < MAX_ATTEMPTS && now.isBefore(expiresAt);
    }

    // a wrong code: it must stay written, so the caller does not throw after it (guide 24, 4.1)
    void failedAttempt() {
        if (attempts >= MAX_ATTEMPTS) {
            throw new IllegalStateException("No attempts left on login code " + id);
        }
        attempts++;
    }

    boolean exhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    void use(Instant now) {
        if (usedAt != null) {
            throw new IllegalStateException("Login code " + id + " already used");
        }
        usedAt = Objects.requireNonNull(now, "now must not be null");
    }

    Long getId() {
        return id;
    }

    LoginChannel getChannel() {
        return channel;
    }

    CodePurpose getPurpose() {
        return purpose;
    }

    Long getRequestedBy() {
        return requestedBy;
    }

    boolean isMessageSent() {
        return messageSent;
    }

    String getDestination() {
        return destination;
    }

    String getCodeHash() {
        return codeHash;
    }

    InetAddress getRequestedFrom() {
        return requestedFrom;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getUsedAt() {
        return usedAt;
    }
}
