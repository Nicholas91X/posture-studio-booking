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

    LoginCode(LoginChannel channel, String destination, String codeHash, InetAddress requestedFrom, Instant now) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
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
