package com.posturestudio.booking.booking;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A booking of a customer with a practitioner (V4). Package-private: other modules use the booking
// service, never the entity (D-002, D-026).
// Piece 1 only maps the table (ddl-auto=validate checks it) for BookingOccupiedTime: no public
// constructor and no transitions yet. Requests arrive with piece 2, the state machine with piece 3
@Entity
@Table(name = "booking")
class Booking {

    protected Booking() {
        // JPA needs a no-arg constructor
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

    BookingStatus getStatus() {
        return status;
    }
}
