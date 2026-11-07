package com.posturestudio.booking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

import com.posturestudio.booking.shared.Checks;
import com.posturestudio.booking.shared.TimeRange;

// A booking of a customer with a practitioner (V4). Package-private: other modules use BookingService,
// never the entity (D-002, D-026).
// Created only through named factory methods, which say where a booking comes from (piece 5 adds the panel).
// Transitions arrive with the state machine of piece 3
@Entity
@Table(name = "booking")
class Booking {

    // while the customer pays, the slot is held for them: the same length as the Stripe session (D-008)
    static final Duration HOLD = Duration.ofMinutes(30);

    protected Booking() {
        // JPA needs a no-arg constructor
    }

    // A request of the customer, who is about to pay by card: PENDING_PAYMENT, held until now + HOLD.
    // The price is the one of the service right now, copied (D-011). Rules (slot, limit) are checked
    // by BookingService before: here only the shape of the data
    static Booking requestedByCustomer(long customerId, long practitionerId, long serviceId, TimeRange slot,
                                       long priceCents, Instant now) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Booking booking = new Booking();
        booking.customerId = customerId;
        booking.practitionerId = practitionerId;
        booking.serviceId = serviceId;
        booking.startsAt = slot.start();
        booking.endsAt = slot.end();
        booking.status = BookingStatus.PENDING_PAYMENT;
        booking.bookedBy = BookedBy.CUSTOMER;
        booking.paidWith = PaidWith.CARD;
        booking.priceCents = Checks.requirePositive(priceCents, "priceCents");
        booking.holdExpiresAt = now.plus(HOLD);
        return booking;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // customer, practitioner and service belong to other modules: only the ids (D-026)
    @Column(updatable = false)
    private Long customerId;

    @Column(updatable = false)
    private Long practitionerId;

    @Column(updatable = false)
    private Long serviceId;

    @Column(updatable = false)
    private Instant startsAt;

    @Column(updatable = false)
    private Instant endsAt;

    @Column
    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private BookedBy bookedBy;

    // updatable: a payment link may turn IN_STUDIO into CARD (requirements, open point 16)
    @Column
    @Enumerated(EnumType.STRING)
    private PaidWith paidWith;

    // copied from the service at booking time (D-011)
    @Column(updatable = false)
    private long priceCents;

    @Column(updatable = false)
    private Instant holdExpiresAt;

    @Column
    private Instant approvalDeadline;

    @Column
    private Instant decidedAt;

    @Column
    private Instant cancelledAt;

    // optimistic lock: two concurrent changes of the same booking cannot both win (piece 3)
    @Version
    private long version;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    Long getId() {
        return id;
    }

    Long getCustomerId() {
        return customerId;
    }

    Long getPractitionerId() {
        return practitionerId;
    }

    Long getServiceId() {
        return serviceId;
    }

    Instant getStartsAt() {
        return startsAt;
    }

    Instant getEndsAt() {
        return endsAt;
    }

    BookingStatus getStatus() {
        return status;
    }

    BookedBy getBookedBy() {
        return bookedBy;
    }

    PaidWith getPaidWith() {
        return paidWith;
    }

    long getPriceCents() {
        return priceCents;
    }

    Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    long getVersion() {
        return version;
    }
}
