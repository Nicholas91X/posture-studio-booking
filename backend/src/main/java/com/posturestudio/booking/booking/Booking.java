package com.posturestudio.booking.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.*;

import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.Checks;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.TimeRange;

// A booking of a customer with a practitioner (V4). Package-private: other modules use BookingService,
// never the entity (D-002, D-026).
// Created only through named factory methods, which say where a booking comes from (piece 5 adds the panel).
// The status changes only through moveTo, which asks the state machine (BookingStatus.canMoveTo):
// every transition has a domain name and writes its own moment column (guide 20, part 2)
@Entity
@Table(name = "booking")
class Booking {

    // While the customer pays, the slot is held for them, and the Stripe session expires at the same instant
    // (D-008). 32 and not 30 minutes (D-061): Stripe wants a session to last at least 30 minutes from ITS
    // creation, which comes a moment after the request; the 2 minutes cover that moment, a retry and clocks
    // that do not agree (guide 31, part 5)
    static final Duration HOLD = Duration.ofMinutes(32);

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
        booking.createdAt = now;
        return booking;
    }

    // Booked by the practitioner for their customer, to be paid in the studio (D-024, D-040): born CONFIRMED,
    // decided now by the practitioner. Not a transition: the state machine is about steps, not birth.
    // The only way to create a confirmed booking; no hold, no approval
    static Booking bookedFromPanel(long customerId, long practitionerId, long serviceId, TimeRange slot,
                                   long priceCents, Instant now) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Booking booking = new Booking();
        booking.customerId = customerId;
        booking.practitionerId = practitionerId;
        booking.serviceId = serviceId;
        booking.startsAt = slot.start();
        booking.endsAt = slot.end();
        booking.status = BookingStatus.CONFIRMED;
        booking.bookedBy = BookedBy.PRACTITIONER;
        booking.paidWith = PaidWith.IN_STUDIO;
        booking.priceCents = Checks.requirePositive(priceCents, "priceCents");
        booking.decidedAt = now;
        booking.createdAt = now;
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

    // optimistic lock, the second defense: transitions take a pessimistic lock on the row first
    // (guide 20, 3.4), so this only catches a future write that forgets it
    @Version
    private long version;

    // The moment of the request, from the Clock and not from the default of the database (as the other
    // tables do): the approval window counts from it (D-030, guide 20, 6.1), and tests fix the clock
    @Column(updatable = false)
    private Instant createdAt;

    // ***** TRANSITIONS *****

    // The card hold is in place: the practitioner has until request + window to answer (D-030).
    // window <= min_booking_notice_hours, and the slot starts more than the notice after the request,
    // so the deadline always comes before the session
    void paymentAuthorized(Duration approvalWindow) {
        Objects.requireNonNull(approvalWindow, "approvalWindow must not be null");
        moveTo(BookingStatus.PENDING_APPROVAL);
        this.approvalDeadline = createdAt.plus(approvalWindow);
    }

    // approved by the practitioner, or by the system in AUTOMATIC mode
    void approve(Instant now) {
        moveTo(BookingStatus.CONFIRMED);
        this.decidedAt = Objects.requireNonNull(now, "now must not be null");
    }

    void reject(Instant now) {
        moveTo(BookingStatus.REJECTED);
        this.decidedAt = Objects.requireNonNull(now, "now must not be null");
    }

    // the customer gives up while waiting for the answer: nothing is charged
    void withdraw(Instant now) {
        moveTo(BookingStatus.WITHDRAWN);
        this.cancelledAt = Objects.requireNonNull(now, "now must not be null");
    }

    // nobody answered in time: void, like a rejection (D-030). Done by the job of guide 21
    void expireApproval() {
        moveTo(BookingStatus.APPROVAL_EXPIRED);
    }

    // A forbidden step is a 409, not a bug (D-027): it really happens, e.g. approving a request
    // the customer has just withdrawn. Nothing changes if the step is refused
    private void moveTo(BookingStatus next) {
        if (!status.canMoveTo(next)) {
            throw new BusinessRuleException(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED,
                    "Booking " + id + " cannot go from " + status + " to " + next);
        }
        this.status = next;
    }

    // ***** QUESTIONS *****

    // Holds the time for whoever looks at instant now: a holding state, and not a pending payment whose
    // hold has expired (guide 18, 3.5). The same rule as the two queries of BookingRepository that say
    // "b.status in :holding and (b.status <> :pending or b.holdExpiresAt > :now)"
    boolean holdsTimeAt(Instant now) {
        return BookingStatus.HOLDS_TIME.contains(status)
                && (status != BookingStatus.PENDING_PAYMENT || holdExpiresAt.isAfter(now));
    }

    TimeRange range() {
        return new TimeRange(startsAt, endsAt);
    }

    // ***** GETTERS *****

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

    Instant getApprovalDeadline() {
        return approvalDeadline;
    }

    Instant getDecidedAt() {
        return decidedAt;
    }

    Instant getCancelledAt() {
        return cancelledAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }
}
