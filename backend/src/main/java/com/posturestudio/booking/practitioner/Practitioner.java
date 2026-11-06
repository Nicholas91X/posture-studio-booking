package com.posturestudio.booking.practitioner;

import static com.posturestudio.booking.shared.Checks.requireBetween;
import static com.posturestudio.booking.shared.Checks.requireOneOf;
import static com.posturestudio.booking.shared.Checks.requirePositive;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// Package-private: other modules use PractitionerService, never the entity (D-002, D-026)
@Entity
@Table(name = "practitioner")
class Practitioner {

    // half-hour starts on or off (D-033): the same values as practitioner_slot_step_minutes_ck
    static final Set<Integer> SLOT_STEPS = Set.of(30, 60);

    // well within the life of the hold on the card, about 7 days (D-031, D-042):
    // the same limit as practitioner_approval_window_hours_ck and the @Max of PractitionerSettings
    static final int MAX_APPROVAL_WINDOW_HOURS = 48;

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

    @Column
    private int minBookingNoticeHours;

    @Column
    private int bookingHorizonDays;

    @Column
    private int maxFutureBookings;

    @Column
    @Enumerated(EnumType.STRING)
    private BookingApproval bookingApproval;

    @Column
    private int approvalWindowHours;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // The entity protects its own state, whoever calls it.
    // Invalid values should be stopped earlier by request validation: here they are a programming error.
    // All values are checked before any field changes: an invalid change leaves the entity as it was
    void changeSettings(PractitionerSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        int notice = requirePositive(settings.cancellationNoticeHours(), "cancellationNoticeHours");
        int validity = requirePositive(settings.singleCreditValidityDays(), "singleCreditValidityDays");
        int step = requireOneOf(settings.slotStepMinutes(), SLOT_STEPS, "slotStepMinutes");
        int bookingNotice = requirePositive(settings.minBookingNoticeHours(), "minBookingNoticeHours");
        int horizon = requirePositive(settings.bookingHorizonDays(), "bookingHorizonDays");
        int maxFuture = requirePositive(settings.maxFutureBookings(), "maxFutureBookings");
        BookingApproval approval = Objects.requireNonNull(settings.bookingApproval(), "bookingApproval must not be null");
        // the answer comes before the session starts (D-030): the same rule as the database check
        int window = requireBetween(settings.approvalWindowHours(), 1,
                Math.min(bookingNotice, MAX_APPROVAL_WINDOW_HOURS), "approvalWindowHours");
        this.cancellationNoticeHours = notice;
        this.singleCreditValidityDays = validity;
        this.slotStepMinutes = step;
        this.minBookingNoticeHours = bookingNotice;
        this.bookingHorizonDays = horizon;
        this.maxFutureBookings = maxFuture;
        this.bookingApproval = approval;
        this.approvalWindowHours = window;
    }

    PractitionerSettings settings() {
        return new PractitionerSettings(cancellationNoticeHours, singleCreditValidityDays, slotStepMinutes,
                minBookingNoticeHours, bookingHorizonDays, maxFutureBookings, bookingApproval, approvalWindowHours);
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
