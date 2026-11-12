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
                                       long priceCents, Instant now, Duration cancellationNotice) {
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
        booking.photographNotice(cancellationNotice);
        return booking;
    }

    // A request of the customer paid with a credit (step 10, guide 38): nothing to pay, so it is born waiting for
    // the practitioner, with the same deadline a paid request gets (D-030): request + window, from createdAt.
    // The credit is committed by BookingService right after the insert (-1 on the lot). Not a transition:
    // the state machine is about steps, not birth (as bookedFromPanel)
    static Booking requestedWithCredit(long customerId, long practitionerId, long serviceId, TimeRange slot,
                                       long priceCents, Instant now, Duration approvalWindow,
                                       Duration cancellationNotice) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(approvalWindow, "approvalWindow must not be null");
        Booking booking = new Booking();
        booking.customerId = customerId;
        booking.practitionerId = practitionerId;
        booking.serviceId = serviceId;
        booking.startsAt = slot.start();
        booking.endsAt = slot.end();
        booking.status = BookingStatus.PENDING_APPROVAL;
        booking.bookedBy = BookedBy.CUSTOMER;
        booking.paidWith = PaidWith.CREDIT;
        // the value of the session, photographed as for a card (D-011): useful to step 11
        booking.priceCents = Checks.requirePositive(priceCents, "priceCents");
        booking.createdAt = now;
        booking.approvalDeadline = now.plus(approvalWindow);
        booking.photographNotice(cancellationNotice);
        return booking;
    }

    // Booked by the practitioner for their customer (D-024): born CONFIRMED, decided now by the practitioner.
    // Not a transition: the state machine is about steps, not birth. No hold, no approval.
    // Paid with a credit of the customer when there is one (D-024, guide 39), committed by BookingService right
    // after the insert; otherwise to be paid in the studio (D-040). Nothing else from the panel
    static Booking bookedFromPanel(long customerId, long practitionerId, long serviceId, TimeRange slot,
                                   long priceCents, Instant now, PaidWith paidWith, Duration cancellationNotice) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (paidWith != PaidWith.IN_STUDIO && paidWith != PaidWith.CREDIT) {
            throw new IllegalArgumentException("A booking from the panel is paid in the studio or with a credit, not "
                    + paidWith);
        }
        Booking booking = new Booking();
        booking.customerId = customerId;
        booking.practitionerId = practitionerId;
        booking.serviceId = serviceId;
        booking.startsAt = slot.start();
        booking.endsAt = slot.end();
        booking.status = BookingStatus.CONFIRMED;
        booking.bookedBy = BookedBy.PRACTITIONER;
        booking.paidWith = paidWith;
        booking.priceCents = Checks.requirePositive(priceCents, "priceCents");
        booking.decidedAt = now;
        booking.createdAt = now;
        booking.photographNotice(cancellationNotice);
        return booking;
    }

    // The notice of cancellation of the practitioner, photographed at birth like the price (D-011, D-078,
    // guide 42, part 6): the rule the customer saw when booking. Changing the setting later moves nothing.
    // The same for every booking, from the panel too: paid by a payment link, it becomes a card booking (D-059)
    private void photographNotice(Duration cancellationNotice) {
        Objects.requireNonNull(cancellationNotice, "cancellationNotice must not be null");
        if (cancellationNotice.isNegative() || cancellationNotice.isZero()) {
            throw new IllegalArgumentException("the notice must be positive: " + cancellationNotice);
        }
        this.freeCancellationUntil = startsAt.minus(cancellationNotice);
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

    // starts_at minus the notice, photographed at birth (V16): until when a cancellation of the customer is in
    // time. Whether that instant itself still is, is the rule of piece 2
    @Column(updatable = false)
    private Instant freeCancellationUntil;

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

    // To be paid in the studio, and still waiting for that: the only state a payment link is for (D-059)
    boolean awaitsPaymentInStudio() {
        return status == BookingStatus.CONFIRMED && paidWith == PaidWith.IN_STUDIO;
    }

    // Paid with a payment link (D-059, guide 34, part 6): from now on it counts as paid by card, and so, cancelled
    // in time, it gives a credit (step 11). Only after the capture succeeded (D-063)
    void paidByLink() {
        if (!awaitsPaymentInStudio()) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_LINK_NOT_ALLOWED,
                    "Booking " + id + " is not waiting to be paid in the studio");
        }
        this.paidWith = PaidWith.CARD;
    }

    // A forbidden step is a 409, not a bug (D-027): it really happens, e.g. approving a request
    // the customer has just withdrawn. Nothing changes if the step is refused
    private void moveTo(BookingStatus next) {
        checkMove(next);
        this.status = next;
    }

    // The same question without the change: asked BEFORE an effect outside the database (the capture), so that a
    // forbidden step never reaches Stripe and a refused capture leaves the booking as it was (guide 33, part 3)
    void checkMove(BookingStatus next) {
        if (!status.canMoveTo(next)) {
            throw new BusinessRuleException(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED,
                    "Booking " + id + " cannot go from " + status + " to " + next);
        }
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

    Instant getFreeCancellationUntil() {
        return freeCancellationUntil;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    long getVersion() {
        return version;
    }
}
