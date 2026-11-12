package com.posturestudio.booking.credit;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Immutable;
import jakarta.persistence.*;

import com.posturestudio.booking.shared.Checks;

// One line of the ledger (V15, D-009, guide 36, part 5): never changed, never deleted. @Immutable tells
// Hibernate never to write an update for it; the database refuses update, delete and truncate anyway
// (credit_movement_append_only_tg), and a debit that would take the lot below zero
// (credit_movement_keep_balance_tg).
// One factory per reason, with the shape credit_movement_shape_ck demands
@Entity
@Immutable
@Table(name = "credit_movement")
class CreditMovement {

    protected CreditMovement() {
        // JPA needs a no-arg constructor
    }

    // +N: the lot is born
    static CreditMovement lotCreated(long lotId, int credits, Instant now) {
        return movement(lotId, Checks.requirePositive(credits, "credits"), MovementReason.LOT_CREATED, null, now);
    }

    // +1: the lot of 1 born from the cancellation of this booking (V16). The booking is here so that the index
    // "one return per booking" sees this lot as the credit given back: never a second one (guide 42, 7.1)
    static CreditMovement lotCreatedByCancellation(long lotId, long bookingId, Instant now) {
        return movement(lotId, 1, MovementReason.LOT_CREATED, bookingId, now);
    }

    // +1 on the same lot: a session cancelled in time, or by the studio, gives its credit back (D-076)
    static CreditMovement cancellationRestore(long lotId, long bookingId, Instant now) {
        return movement(lotId, 1, MovementReason.CANCELLATION_RESTORE, bookingId, now);
    }

    // -1: the credit is committed to a booking
    static CreditMovement booking(long lotId, long bookingId, Instant now) {
        return movement(lotId, -1, MovementReason.BOOKING, bookingId, now);
    }

    // +1: the booking let it go (rejected, unanswered, withdrawn)
    static CreditMovement bookingRelease(long lotId, long bookingId, Instant now) {
        return movement(lotId, 1, MovementReason.BOOKING_RELEASE, bookingId, now);
    }

    // a correction of the practitioner (D-067): any amount but zero, always with who and why
    static CreditMovement adjustment(long lotId, int amount, long createdBy, String note, Instant now) {
        if (amount == 0) {
            throw new IllegalArgumentException("an adjustment must change something");
        }
        CreditMovement movement = movement(lotId, amount, MovementReason.ADJUSTMENT, null, now);
        movement.createdBy = createdBy;
        movement.note = Checks.requireNotBlank(note, "note");
        return movement;
    }

    private static CreditMovement movement(long lotId, int amount, MovementReason reason, Long bookingId,
                                           Instant now) {
        CreditMovement movement = new CreditMovement();
        movement.creditLotId = lotId;
        movement.amount = amount;
        movement.reason = reason;
        movement.bookingId = bookingId;
        movement.createdAt = Objects.requireNonNull(now, "now must not be null");
        return movement;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long creditLotId;

    @Column
    private int amount;

    @Column
    @Enumerated(EnumType.STRING)
    private MovementReason reason;

    // booking belongs to another module: only the id (D-026)
    @Column
    private Long bookingId;

    // ADJUSTMENT only
    @Column
    private Long createdBy;

    @Column
    private String note;

    @Column
    private Instant createdAt;

    Long getId() {
        return id;
    }

    long getCreditLotId() {
        return creditLotId;
    }

    int getAmount() {
        return amount;
    }

    MovementReason getReason() {
        return reason;
    }

    Long getBookingId() {
        return bookingId;
    }

    Long getCreatedBy() {
        return createdBy;
    }

    String getNote() {
        return note;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
