package com.posturestudio.booking.schedule;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// An absence of the practitioner: a single event, so instants (D-035). Never changed:
// to correct one, it is deleted and created again. Absences may overlap each other
@Entity
@Table(name = "unavailability")
class Unavailability {

    static final int MAX_REASON_LENGTH = 200;

    protected Unavailability() {
        // JPA needs a no-arg constructor
    }

    Unavailability(Long practitionerId, Instant startsAt, Instant endsAt, String reason) {
        this.practitionerId = Objects.requireNonNull(practitionerId, "practitionerId must not be null");
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("unavailability must end after it starts");
        }
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = normalize(reason);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // practitioner belongs to another module: only the id (D-026)
    @Column(updatable = false)
    private Long practitionerId;

    @Column(updatable = false)
    private Instant startsAt;

    @Column(updatable = false)
    private Instant endsAt;

    // for the practitioner only, never in public APIs
    @Column(updatable = false)
    private String reason;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // a blank reason is no reason
    private static String normalize(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String stripped = reason.strip();
        if (stripped.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return stripped;
    }

    Long getId() {
        return id;
    }

    Instant getStartsAt() {
        return startsAt;
    }

    Instant getEndsAt() {
        return endsAt;
    }

    String getReason() {
        return reason;
    }
}
