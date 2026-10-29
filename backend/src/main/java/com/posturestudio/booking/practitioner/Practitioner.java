package com.posturestudio.booking.practitioner;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// Package-private: other modules use PractitionerService, never the entity (D-002, D-026)
@Entity
@Table(name = "practitioner")
class Practitioner {

    protected Practitioner() {
        // JPA needs a no-arg constructor.
        // No public constructor yet: practitioners are created by the admin (D-021, step 8)
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // app_user belongs to the auth module: only the id, never the entity (D-026)
    @Column(updatable = false)
    private Long userId;

    @Column
    private String firstName;

    @Column
    private String lastName;

    @Column
    private int cancellationNoticeHours;

    @Column
    private int singleCreditValidityDays;

    @Column
    private int slotStepMinutes;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // The entity protects its own state, whoever calls it.
    // Invalid values should be stopped earlier by request validation: here they are a programming error
    void changeSettings(PractitionerSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        this.cancellationNoticeHours = requirePositive(settings.cancellationNoticeHours(), "cancellationNoticeHours");
        this.singleCreditValidityDays = requirePositive(settings.singleCreditValidityDays(), "singleCreditValidityDays");
        this.slotStepMinutes = requirePositive(settings.slotStepMinutes(), "slotStepMinutes");
    }

    PractitionerSettings settings() {
        return new PractitionerSettings(cancellationNoticeHours, singleCreditValidityDays, slotStepMinutes);
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
        return value;
    }

    Long getId() {
        return id;
    }

    Long getUserId() {
        return userId;
    }

    String getFirstName() {
        return firstName;
    }

    String getLastName() {
        return lastName;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
