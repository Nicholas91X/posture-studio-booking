package com.posturestudio.booking.booking;

import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingCancelled;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.BookingEvent.LatePaymentReleased;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.ServiceOnSale;
import com.posturestudio.booking.credit.CancelledBy;
import com.posturestudio.booking.credit.CreditService;
import com.posturestudio.booking.credit.ReturnedCredit;
import com.posturestudio.booking.customer.CustomerName;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.practitioner.PractitionerSettings;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;
import com.posturestudio.booking.shared.StudioTime;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The only door of the booking module (D-002): returns records, never the entity.
 * Every customer method receives the id of the customer who acts and finds only what is theirs.
 * Dependencies: booking -> customer, catalog, practitioner, schedule, credit. Nobody depends on booking:
 * schedule reaches it only through OccupiedTime (D-044), payment through CardHolds and CardCheckout.
 *
 * Transactions run with the default isolation of PostgreSQL, READ COMMITTED: every statement sees the
 * data committed when it starts. The customer lock relies on it (guide 19, 4.7): with REPEATABLE READ
 * the count after the lock could work on a snapshot taken before the wait, and the limit would not hold.
 *
 * Transitions (guide 20): every one locks the booking row first (select ... for update), changes the
 * status in memory through the state machine, only then calls the card (CardHolds), and publishes its
 * event. A refused step never reaches Stripe; a failed capture rolls everything back, event included.
 */
@Service
public class BookingService {

    private static final String SQL_STATE_EXCLUSION_VIOLATION = "23P01";
    private static final String SQL_STATE_DEADLOCK = "40P01";
    private static final String NO_OVERLAP_CONSTRAINT = "booking_no_overlap_ex";

    private final BookingRepository bookings;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final ScheduleService schedule;
    private final CardHolds cards;
    private final CardCheckout checkout;
    private final CreditService credits;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BookingService(BookingRepository bookings, CatalogService catalog, CustomerService customers,
                   PractitionerService practitioners, ScheduleService schedule, CardHolds cards,
                   CardCheckout checkout, CreditService credits, ApplicationEventPublisher events, Clock clock) {
        this.bookings = bookings;
        this.catalog = catalog;
        this.customers = customers;
        this.practitioners = practitioners;
        this.schedule = schedule;
        this.cards = cards;
        this.checkout = checkout;
        this.credits = credits;
        this.events = events;
        this.clock = clock;
    }

    // ***** CUSTOMER *****

    // A customer asks for a slot (guide 19, part 2 for the order; guide 38, part 4 for the credit). One
    // transaction, one reading of the clock. Outcome, with a usable credit: PENDING_APPROVAL with the credit
    // committed, nothing to pay (or CONFIRMED in AUTOMATIC mode). Without: PENDING_PAYMENT, held for 32 minutes
    // (D-008, D-061), with the page where the customer pays (guide 31), until paymentAuthorized
    @Transactional
    public CustomerBookingView request(long customerId, NewBooking request) {
        Instant now = clock.instant();
        long practitionerId = request.practitionerId();

        // 0. FIRST the lock against a deactivation in flight (guide 45, part 4), THEN everything that asks whether
        // the practitioner is active (the service on sale, step 1): asked after the wait, it sees the deactivation
        practitioners.lockForBooking(practitionerId);

        Instant start = request.startsAt().toInstant();

        // 1. the request names something on sale (404): no lock needed for that
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, request.serviceId());

        // 1b. neither a credit nor a card account: nobody can pay this booking, refused before any lock or write,
        // so no slot is ever held for it (guide 30, part 6). A quick look without locks: it decides nothing,
        // step 5 decides. A practitioner with no account still takes the bookings of a customer with a credit
        if (!checkout.available(practitionerId)
                && !credits.hasUsableCredit(customerId, practitionerId, service.id(), start)) {
            throw cardPaymentsUnavailable(practitionerId);
        }

        // 2. from here on, the requests of this customer go one at a time (guide 19, part 4):
        // a second one waits here until this transaction ends, then counts again and sees this booking
        customers.lockForBooking(customerId);

        // 3. the limit before the slot: with 4 bookings already, "slot not available" would only make
        // the customer try another one in vain
        PractitionerSettings settings = practitioners.getSettings(practitionerId);
        long future = bookings.countFutureHolding(customerId, practitionerId, now,
                BookingStatus.HOLDS_TIME, BookingStatus.PENDING_PAYMENT);
        if (future >= settings.maxFutureBookings()) {
            throw new BusinessRuleException(ErrorCode.BOOKING_LIMIT_REACHED,
                    "Customer " + customerId + " already has " + future + " future bookings with practitioner "
                            + practitionerId);
        }

        // 4. grid, working hours, notice, horizon, absences, bookings: the same calculation the customer saw
        TimeRange slot = schedule.requireFreeSlot(practitionerId, service.durationMinutes(), start, now);

        // 5. an expired hold on this slot still holds it for the database constraint (guide 18, 3.5):
        // close it now instead of waiting for the job
        bookings.expireStaleHolds(practitionerId, slot.start(), slot.end(), now,
                BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED);

        // 6. credit or card, decided here, under the lock on the customer (guide 38, parts 4 to 6): the usable lot
        // that ends first, itself locked. The limit above holds for credits too: it protects the calendar
        var lot = credits.lockUsableLot(customerId, practitionerId, service.id(), slot.start());
        if (lot.isPresent()) {
            return requestWithCredit(customerId, practitionerId, service, slot, lot.get(), settings, now);
        }
        // the credit of step 1b is gone in the meantime (rare), and nobody can pay by card
        if (!checkout.available(practitionerId)) {
            throw cardPaymentsUnavailable(practitionerId);
        }

        // 7. the insert, flushed here and not at commit, so that a violation can be translated
        Booking booking = Booking.requestedByCustomer(customerId, practitionerId, service.id(), slot,
                service.priceCents(), now, cancellationNotice(settings));
        insert(booking, practitionerId, slot);

        // 8. the Stripe page, inside this transaction (guide 31, parts 3 and 4): if Stripe fails, the booking
        // is rolled back with everything else; if our commit fails after Stripe answered, a session nobody
        // knows expires by itself. The page expires with the hold, never after it
        URI page = checkout.start(new CardCheckout.CheckoutRequest(booking.getId(), practitionerId, customerId,
                service.id(), booking.getPriceCents(), booking.getHoldExpiresAt()));
        return CustomerBookingView.from(booking, page);
    }

    // Paid with a credit (guide 38): born PENDING_APPROVAL, the credit committed (-1) after the insert, then the
    // same path as after a payment (D-030): AUTOMATIC confirms at once, MANUAL asks the practitioner. Nothing to
    // capture, so the confirmation of AUTOMATIC cannot be refused
    private CustomerBookingView requestWithCredit(long customerId, long practitionerId, ServiceOnSale service,
                                                  TimeRange slot, long lotId, PractitionerSettings settings,
                                                  Instant now) {
        Booking booking = Booking.requestedWithCredit(customerId, practitionerId, service.id(), slot,
                service.priceCents(), now, Duration.ofHours(settings.approvalWindowHours()),
                cancellationNotice(settings));
        insert(booking, practitionerId, slot);
        credits.commit(lotId, booking.getId());
        switch (settings.bookingApproval()) {
            case AUTOMATIC -> confirmPaid(booking, now);
            case MANUAL -> events.publishEvent(ApprovalRequested.of(booking));
        }
        return CustomerBookingView.from(booking);
    }

    // the notice of the practitioner now, photographed into the booking (guide 42, part 6)
    private static Duration cancellationNotice(PractitionerSettings settings) {
        return Duration.ofHours(settings.cancellationNoticeHours());
    }

    private void insert(Booking booking, long practitionerId, TimeRange slot) {
        try {
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw translateOverlap(e, practitionerId, slot);
        }
    }

    private static BusinessRuleException cardPaymentsUnavailable(long practitionerId) {
        return new BusinessRuleException(ErrorCode.CARD_PAYMENTS_UNAVAILABLE,
                "Practitioner " + practitionerId + " cannot take card payments yet");
    }

    @Transactional(readOnly = true)
    public CustomerBookingView findForCustomer(long customerId, long bookingId) {
        Booking booking = bookings.findByIdAndCustomerId(bookingId, customerId).orElseThrow(() -> notFound(bookingId));
        return customerView(booking, clock.instant(), credits.returnedTo(List.of(booking.getId())));
    }

    // While waiting for the answer the customer can give up, nothing charged (D-030). Allowed even after
    // the deadline: the outcome for the customer is the same as the expiry the job is about to do
    @Transactional
    public CustomerBookingView withdraw(long customerId, long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> notFound(bookingId));
        booking.withdraw(now);
        releasePayment(booking);
        events.publishEvent(BookingWithdrawn.of(booking));
        return customerView(booking, now, credits.returnedTo(List.of(booking.getId())));
    }

    // The customer gives up a CONFIRMED session (guide 43). In time (until free_cancellation_until, included, V16):
    // the credit comes back, or a new credit for a card; nothing for a session to be paid in the studio. Late: nothing.
    // One transaction with the session locked (D-046): a second request, or a webhook of a payment link, waits here
    // and then sees the session as it really is (guide 43, part 7). The open payment link of a session to be paid in
    // the studio is closed AFTER the commit, by payment, outside this lock (guide 43, part 5).
    // cancelledBy: the account that acts, for the trace (V16)
    @Transactional
    public CustomerBookingView cancel(long customerId, long cancelledBy, long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> notFound(bookingId));
        // the second click, or a retry after a lost response: the same answer, no new effect (guide 43, part 6)
        if (booking.isCancelledByCustomer()) {
            return customerView(booking, now, credits.returnedTo(List.of(booking.getId())));
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED && booking.hasStartedAt(now)) {
            throw new BusinessRuleException(ErrorCode.BOOKING_ALREADY_STARTED,
                    "Booking " + bookingId + " started at " + booking.getStartsAt());
        }
        // any state but CONFIRMED: 409 from the state machine (a request in wait is withdrawn, not cancelled)
        BookingStatus outcome = booking.cancelByCustomer(now, cancelledBy);
        ReturnedCredit returned = outcome == BookingStatus.CANCELLED_IN_TIME
                ? giveBack(booking, CancelledBy.CUSTOMER) : null;
        events.publishEvent(BookingCancelled.of(booking, returned == null ? null : returned.validUntil()));
        return CustomerBookingView.from(booking, null, returned);
    }

    // What a session gives back when cancelled in time, or by the studio (guide 43, part 4.1). A card was already
    // captured (a confirmed card session always is, D-063, D-065): nothing to ask Stripe, a new credit of 1. Valid
    // single_credit_validity_days from today, read now: the validity of a NEW credit, not a rule the customer agreed
    // to when booking (unlike the notice, guide 42, 8.4). A switch expression: a new way of paying does not compile
    // until someone decides what its cancellation gives back
    private ReturnedCredit giveBack(Booking booking, CancelledBy who) {
        int days = practitioners.getSettings(booking.getPractitionerId()).singleCreditValidityDays();
        return switch (booking.getPaidWith()) {
            case CREDIT -> credits.restoreOnCancellation(booking.getId(), days, who);
            case CARD -> credits.lotFromCancellation(booking.getCustomerId(), booking.getPractitionerId(),
                    booking.getServiceId(), booking.getId(), days);
            // nothing was paid (D-040)
            case IN_STUDIO -> null;
        };
    }

    // ***** PAYMENT *****

    // A card hold is in place: called by the Stripe webhook (guide 32), which has already made sure that
    // THIS payment is new. One path for both approval modes (D-030): PENDING_APPROVAL first, then AUTOMATIC
    // approves at once with the same confirm() a practitioner uses, inside the same transaction.
    // It answers instead of acting (guide 32, part 7): only the webhook knows which payment arrived, so it is
    // the webhook that releases a payment the booking does not want
    @Transactional
    public PaymentOutcome paymentAuthorized(long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedById(bookingId).orElseThrow(() -> notFound(bookingId));

        // A PENDING_PAYMENT whose hold expired is still accepted: nobody took the slot, or the lazy expiry
        // would have made it EXPIRED (D-046). Any other state does not want this money, with no error: for
        // Stripe an error means "try again" (D-031, D-059, guide 32, part 7)
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            if (booking.getStatus() == BookingStatus.EXPIRED) {
                // paid too late: the slot may be someone else's. The customer saw "paid" on Stripe's page,
                // and is told by email, after the commit, that nothing was charged (guide 32, part 9)
                events.publishEvent(LatePaymentReleased.of(booking));
            }
            return new PaymentOutcome.NotWanted(booking.getStatus());
        }

        PractitionerSettings settings = practitioners.getSettings(booking.getPractitionerId());
        booking.paymentAuthorized(Duration.ofHours(settings.approvalWindowHours()));
        switch (settings.bookingApproval()) {
            case AUTOMATIC -> {
                // a capture refused right after the payment (a hold released by hand on Stripe): the request goes
                // to the practitioner as in manual mode, who decides. No exception: the webhook would make Stripe
                // retry for three days, and the answer would never change (guide 33, part 4)
                if (!confirmPaid(booking, now)) {
                    events.publishEvent(ApprovalRequested.of(booking));
                }
            }
            case MANUAL -> events.publishEvent(ApprovalRequested.of(booking));
        }
        return new PaymentOutcome.Accepted(booking.getStatus());
    }

    // "Stripe captured, our commit failed" (guide 33, part 6): Stripe's payment_intent.succeeded found the payment
    // still AUTHORIZED. A capture happens only after an approval, so confirming respects that decision, WITHOUT
    // capturing again. NotWanted: the booking can no longer be confirmed, and money was taken for it (the webhook
    // logs it as an error: refund by hand from the dashboard, D-015)
    // ***** PAYMENT LINK (D-059, guide 34) *****

    // The minimum life of a Stripe page, with the margin of D-061: a session starting sooner gets no link
    static final Duration LINK_MINIMUM = Duration.ofMinutes(32);

    // Can this session of this practitioner receive a payment link? 404 if it is not theirs, 409 if it is not
    // CONFIRMED and to be paid in the studio, or starts too soon (guide 34, part 4). No lock: the worst a race can
    // do is a link for a session paid a moment ago, whose payment would then be released (guide 34, part 8)
    @Transactional(readOnly = true)
    public LinkTarget linkTarget(long practitionerId, long bookingId) {
        Booking booking = bookings.findByIdAndPractitionerId(bookingId, practitionerId)
                .orElseThrow(() -> notFound(bookingId));
        if (!booking.awaitsPaymentInStudio()) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_LINK_NOT_ALLOWED,
                    "Booking " + bookingId + " is not a confirmed session to be paid in the studio");
        }
        if (booking.getStartsAt().isBefore(clock.instant().plus(LINK_MINIMUM))) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_LINK_NOT_ALLOWED,
                    "Booking " + bookingId + " starts too soon for a payment link");
        }
        return new LinkTarget(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                booking.getServiceId(), booking.getPriceCents(), booking.getStartsAt());
    }

    // The money of a link is held on the card: does the session still want it? (guide 34, part 6). The lock on the
    // session stays until the end of the webhook's transaction, so two payments arriving together are decided one
    // after the other: "the first wins" (D-059, guide 34, part 7)
    @Transactional
    public PaymentOutcome linkPaymentArrived(long bookingId) {
        Booking booking = bookings.findLockedById(bookingId).orElseThrow(() -> notFound(bookingId));
        return booking.awaitsPaymentInStudio() ? new PaymentOutcome.Accepted(booking.getStatus())
                : new PaymentOutcome.NotWanted(booking.getStatus());
    }

    // after the capture succeeded (D-063): from now on the session counts as paid by card
    @Transactional
    public void paidByLink(long bookingId) {
        Booking booking = bookings.findLockedById(bookingId).orElseThrow(() -> notFound(bookingId));
        booking.paidByLink();
    }

    @Transactional
    public PaymentOutcome paymentCaptured(long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedById(bookingId).orElseThrow(() -> notFound(bookingId));
        return switch (booking.getStatus()) {
            case PENDING_APPROVAL -> {
                booking.approve(now);
                events.publishEvent(BookingConfirmed.of(booking));
                yield new PaymentOutcome.Accepted(BookingStatus.CONFIRMED);
            }
            case CONFIRMED -> new PaymentOutcome.Accepted(BookingStatus.CONFIRMED);
            default -> new PaymentOutcome.NotWanted(booking.getStatus());
        };
    }

    // ***** PRACTITIONER *****

    // Booked from the panel for a customer and confirmed at once (guide 22): with a credit of the customer when one
    // covers the session, the same rule as the customer's request (D-024, D-071, guide 39), otherwise paid in the
    // studio (D-040).
    // Working bands and absences apply; grid, notice, horizon, limit and approval do not (guide 22, part 2).
    // Customer found or created by email in this same transaction: a failed booking leaves no new customer
    @Transactional
    public PanelBookingView bookFromPanel(long practitionerId, NewPanelBooking request) {
        Instant now = clock.instant();
        // the lock against a deactivation in flight, first (guide 45, part 4); the practitioner acting here is signed
        // in, but a deactivation may be committing in this very moment
        practitioners.lockForBooking(practitionerId);
        // an own active service, or 404
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, request.serviceId());
        long customerId = customers.findOrCreate(request.customer());
        // it does not respect the limit, but it counts in it: same lock as the customer's request (D-045)
        customers.lockForBooking(customerId);

        Instant start = request.startsAt().toInstant();
        TimeRange slot = new TimeRange(start, start.plus(Duration.ofMinutes(service.durationMinutes())));
        if (!start.isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.SLOT_NOT_AVAILABLE, "A booking cannot start in the past: " + start);
        }
        schedule.requireWorkingTime(practitionerId, slot);

        // the same last steps as a customer's request: stale holds closed, overlap left to the constraint
        bookings.expireStaleHolds(practitionerId, slot.start(), slot.end(), now,
                BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED);
        // the usable lot that ends first, locked: under the lock on the customer taken above (lock order of D-069)
        var lot = credits.lockUsableLot(customerId, practitionerId, service.id(), slot.start());
        Booking booking = Booking.bookedFromPanel(customerId, practitionerId, service.id(), slot,
                service.priceCents(), now, lot.isPresent() ? PaidWith.CREDIT : PaidWith.IN_STUDIO,
                cancellationNotice(practitioners.getSettings(practitionerId)));
        insert(booking, practitionerId, slot);
        // used at once: no request that a rejection could give back; only a cancellation in time will (step 11)
        lot.ifPresent(lotId -> credits.commit(lotId, booking.getId()));
        events.publishEvent(BookingConfirmed.of(booking));
        return PanelBookingView.from(booking);
    }

    @Transactional(readOnly = true)
    public PanelBookingView findForPractitioner(long practitionerId, long bookingId) {
        return bookings.findByIdAndPractitionerId(bookingId, practitionerId)
                .map(PanelBookingView::from)
                .orElseThrow(() -> notFound(bookingId));
    }

    @Transactional
    public PanelBookingView approve(long practitionerId, long bookingId) {
        Instant now = clock.instant();
        Booking booking = lockedForPractitioner(practitionerId, bookingId);
        requireBeforeDeadline(booking, now);
        // the request stays PENDING_APPROVAL: the practitioner may reject it, or let it expire (guide 33, part 5)
        if (!confirmPaid(booking, now)) {
            throw new BusinessRuleException(ErrorCode.CARD_CAPTURE_FAILED,
                    "The amount held for booking " + bookingId + " can no longer be captured");
        }
        return PanelBookingView.from(booking);
    }

    @Transactional
    public PanelBookingView reject(long practitionerId, long bookingId) {
        Booking booking = lockedForPractitioner(practitionerId, bookingId);
        rejectLocked(booking, clock.instant());
        return PanelBookingView.from(booking);
    }

    // The studio cancels one of its future sessions (D-075, guide 44), whatever the notice: the customer loses
    // nothing. A confirmed session becomes CANCELLED_BY_STUDIO and gives back what was paid, with the minimum
    // validity always (CancelledBy.STUDIO, D-076); a request in wait is REJECTED, by the same code as /reject
    // (D-078). The open payment link of a session to be paid in the studio is closed after the commit, as for the
    // customer (PaymentLinkClosing). cancelledBy: the account that acts, for the trace (V16)
    @Transactional
    public PanelCancellationView cancelByStudio(long practitionerId, long cancelledBy, long bookingId) {
        Instant now = clock.instant();
        Booking booking = lockedForPractitioner(practitionerId, bookingId);
        // the second click sees the result of the first: what /cancel produces is one of these two (guide 44, part 4)
        if (booking.getStatus() == BookingStatus.CANCELLED_BY_STUDIO || booking.getStatus() == BookingStatus.REJECTED) {
            return cancellationView(booking);
        }
        if (booking.getStatus() == BookingStatus.PENDING_APPROVAL) {
            rejectLocked(booking, now);
            return cancellationView(booking);
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED && booking.hasStartedAt(now)) {
            throw new BusinessRuleException(ErrorCode.BOOKING_ALREADY_STARTED,
                    "Booking " + bookingId + " started at " + booking.getStartsAt());
        }
        // any other state (paying, cancelled by the customer meanwhile, over): 409 from the state machine
        booking.cancelByStudio(now, cancelledBy);
        ReturnedCredit returned = giveBack(booking, CancelledBy.STUDIO);
        events.publishEvent(BookingCancelled.of(booking, returned == null ? null : returned.validUntil()));
        return new PanelCancellationView(PanelBookingView.from(booking), returned);
    }

    // A rejection on a booking the caller has locked: within the deadline (D-030), the hold released or the credit
    // back to its lot, the email. The one place of it, for /reject and for /cancel of a request in wait
    private void rejectLocked(Booking booking, Instant now) {
        requireBeforeDeadline(booking, now);
        booking.reject(now);
        releasePayment(booking);
        events.publishEvent(BookingRejected.of(booking));
    }

    // what the panel sees of a cancellation: the session and what came back, read from the ledger (one question)
    private PanelCancellationView cancellationView(Booking booking) {
        return new PanelCancellationView(PanelBookingView.from(booking),
                credits.returnedTo(List.of(booking.getId())).get(booking.getId()));
    }

    // ***** AFTER THE SESSION (guide 46, part 2) *****

    // The customer did not come: from the start of the session until 7 days after its end. No effect on credits:
    // what was paid is used, as for a late cancellation (requirements). Repeated, the same answer, the first mark kept
    @Transactional
    public PanelBookingView markNoShow(long practitionerId, long markedBy, long bookingId) {
        Booking booking = attendanceTarget(practitionerId, bookingId, BookingStatus.NO_SHOW);
        if (booking.getStatus() != BookingStatus.NO_SHOW) {
            booking.markNoShow(clock.instant(), markedBy);
        }
        return PanelBookingView.from(booking);
    }

    // The correction: attended after all. Same window, same idempotence
    @Transactional
    public PanelBookingView markAttended(long practitionerId, long markedBy, long bookingId) {
        Booking booking = attendanceTarget(practitionerId, bookingId, BookingStatus.COMPLETED);
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            booking.markAttended(clock.instant(), markedBy);
        }
        return PanelBookingView.from(booking);
    }

    // The session, locked (the job may be closing it in this very moment), with the rules of the window. Asked in this
    // order: a session in another state says so first (409 from the state machine), then the time
    private Booking attendanceTarget(long practitionerId, long bookingId, BookingStatus wanted) {
        Instant now = clock.instant();
        Booking booking = lockedForPractitioner(practitionerId, bookingId);
        if (booking.getStatus() != wanted) {
            booking.checkMove(wanted);
        }
        if (!booking.hasStartedAt(now)) {
            throw new BusinessRuleException(ErrorCode.BOOKING_NOT_STARTED,
                    "Booking " + bookingId + " starts at " + booking.getStartsAt());
        }
        if (booking.attendanceClosedAt(now)) {
            throw new BusinessRuleException(ErrorCode.ATTENDANCE_WINDOW_CLOSED,
                    "Booking " + bookingId + " ended at " + booking.getEndsAt() + ", more than "
                            + Booking.ATTENDANCE_WINDOW.toDays() + " days ago");
        }
        return booking;
    }

    // ***** ADMIN (D-077, guide 45, part 5) *****

    // the sessions that stop the deactivation of this practitioner: what the admin must cancel first. No names of
    // customers: the admin needs to know what to cancel, not who
    @Transactional(readOnly = true)
    public List<AdminBookingView> unfinishedForAdmin(long practitionerId) {
        practitioners.requireExists(practitionerId);
        return unfinished(practitionerId, clock.instant()).stream().map(AdminBookingView::from).toList();
    }

    List<Booking> unfinished(long practitionerId, Instant now) {
        return bookings.findUnfinishedHolding(practitionerId, now, BookingStatus.HOLDS_TIME, BookingStatus.PENDING_PAYMENT);
    }

    // ***** LISTS (guide 23) *****

    // the practitioner's bookings overlapping the local days from..to (both included, in Rome), every state
    @Transactional(readOnly = true)
    public List<PanelBookingRow> listForPanel(long practitionerId, LocalDate from, LocalDate to) {
        Instant windowStart = from.atStartOfDay(StudioTime.ZONE).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(StudioTime.ZONE).toInstant();
        List<Booking> own = bookings.findByPractitionerIdAndStartsAtBeforeAndEndsAtAfterOrderByStartsAt(
                practitionerId, windowEnd, windowStart);
        return rowsFor(practitionerId, own, clock.instant());
    }

    // the requests the practitioner can still answer (D-030): past the deadline they are void
    @Transactional(readOnly = true)
    public List<PanelBookingRow> requestsForPanel(long practitionerId) {
        Instant now = clock.instant();
        List<Booking> waiting = bookings.findByPractitionerIdAndStatusAndApprovalDeadlineAfterOrderByApprovalDeadline(
                practitionerId, BookingStatus.PENDING_APPROVAL, now);
        return rowsFor(practitionerId, waiting, now);
    }

    // "My customers" (D-066, guide 41, part 4): with at least one booking with this practitioner, in any state, or
    // at least one lot of credits with them. Built here, because booking already depends on credit and customer;
    // one question each on a set of ids (D-029). Never the customers of the other practitioner only
    @Transactional(readOnly = true)
    public List<PanelCustomerView> customersForPanel(long practitionerId) {
        Set<Long> ids = new HashSet<>(bookings.findCustomerIdsOf(practitionerId));
        ids.addAll(credits.customerIdsOf(practitionerId));
        return customers.contactsOf(ids).entrySet().stream()
                .map(entry -> new PanelCustomerView(entry.getKey(), entry.getValue().firstName(),
                        entry.getValue().lastName(), entry.getValue().email()))
                .sorted(Comparator.comparing(PanelCustomerView::lastName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PanelCustomerView::firstName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(PanelCustomerView::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerBookingView> listForCustomer(long customerId) {
        return listForCustomer(customerId, null);
    }

    // period null: every session, the furthest in the future first (the list of before, unchanged); UPCOMING: not over,
    // the soonest first; PAST: over, the latest first (guide 46, part 4)
    @Transactional(readOnly = true)
    public List<CustomerBookingView> listForCustomer(long customerId, BookingPeriod period) {
        Instant now = clock.instant();
        List<Booking> own = period == null ? bookings.findByCustomerIdOrderByStartsAtDesc(customerId)
                : switch (period) {
                    case UPCOMING -> bookings.findByCustomerIdAndEndsAtAfterOrderByStartsAtAsc(customerId, now);
                    case PAST -> bookings.findByCustomerIdAndEndsAtLessThanEqualOrderByStartsAtDesc(customerId, now);
                };
        // what came back to each booking: one question for the whole list (D-029)
        Map<Long, ReturnedCredit> returned = credits.returnedTo(own.stream().map(Booking::getId).toList());
        return own.stream()
                .map(booking -> customerView(booking, now, returned))
                .toList();
    }

    // ***** JOBS (guide 21) *****

    // CONFIRMED -> COMPLETED for every session over, in one statement: no card, no event (guide 46, part 1)
    @Transactional
    public int completeSessionsOver() {
        return bookings.completeAllOver(clock.instant(), BookingStatus.CONFIRMED, BookingStatus.COMPLETED);
    }

    // PENDING_PAYMENT -> EXPIRED for every expired hold, in one statement: no card, no event
    @Transactional
    public int expireHolds() {
        return bookings.expireAllStaleHolds(clock.instant(), BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED);
    }

    // the requests past their deadline, to be closed one by one with expireApproval
    @Transactional(readOnly = true)
    public List<Long> expiredApprovals() {
        return bookings.findIdsPastDeadline(clock.instant(), BookingStatus.PENDING_APPROVAL);
    }

    // One request, in a transaction of its own (guide 21, 3.2). Between the list and the lock the
    // practitioner may have answered or the customer withdrawn: after the lock the guard is read again,
    // and if it no longer holds nothing happens, with no error. This also makes two instances of the
    // application safe: the second one finds the request already closed (guide 21, part 5)
    @Transactional
    public void expireApproval(long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PENDING_APPROVAL
                || now.isBefore(booking.getApprovalDeadline())) {
            return;
        }
        booking.expireApproval();
        releasePayment(booking);
        events.publishEvent(ApprovalExpired.of(booking));
    }

    // ***** HELPERS *****

    // The payment page only while the slot is still held for the customer (guide 31, part 9). At most a few
    // such bookings per customer (the limit of future bookings), so one question each is fine
    private CustomerBookingView customerView(Booking booking, Instant now, Map<Long, ReturnedCredit> returned) {
        ReturnedCredit credit = returned.get(booking.getId());
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT && booking.holdsTimeAt(now)) {
            return CustomerBookingView.from(booking, checkout.pageOf(booking.getId()).orElse(null), credit);
        }
        return CustomerBookingView.from(booking, null, credit);
    }

    // The rows of a panel list with names and flags (guide 23, part 2). A fixed number of queries whatever
    // the number of rows: one question per module on a set of ids (D-029), the rest combined in Java.
    // Flags only on bookings that hold the time now: a rejected or expired booking uses no room
    private List<PanelBookingRow> rowsFor(long practitionerId, List<Booking> own, Instant now) {
        if (own.isEmpty()) {
            return List.of();
        }
        List<Booking> holding = own.stream().filter(booking -> booking.holdsTimeAt(now)).toList();

        Set<TimeRange> outside = schedule.outsideWorkingHours(practitionerId,
                holding.stream().map(Booking::range).toList());

        // the other practitioners' bookings in the span of ours: only service and times (BookedRange)
        List<BookedRange> others = holding.isEmpty() ? List.of() : bookings.findOthersHolding(practitionerId,
                holding.stream().map(Booking::getStartsAt).min(Comparator.naturalOrder()).orElseThrow(),
                holding.stream().map(Booking::getEndsAt).max(Comparator.naturalOrder()).orElseThrow(),
                now, BookingStatus.HOLDS_TIME, BookingStatus.PENDING_PAYMENT);
        Set<Long> serviceIds = new HashSet<>();
        holding.forEach(booking -> serviceIds.add(booking.getServiceId()));
        others.forEach(other -> serviceIds.add(other.serviceId()));
        Set<Long> evaluations = catalog.evaluationsAmong(serviceIds);

        Map<Long, CustomerName> names = customers.namesOf(
                own.stream().map(Booking::getCustomerId).collect(Collectors.toSet()));

        return own.stream()
                .map(booking -> {
                    boolean holds = booking.holdsTimeAt(now);
                    return PanelBookingRow.from(booking, names.get(booking.getCustomerId()),
                            holds && outside.contains(booking.range()),
                            holds ? evaluationOverlaps(booking, others, evaluations) : List.of());
                })
                .toList();
    }

    // The intervals this booking shares with the others', when one of the two is an evaluation (D-043).
    // The shared part of two half-open intervals: from the later start to the earlier end
    private static List<RoomOverlap> evaluationOverlaps(Booking booking, List<BookedRange> others, Set<Long> evaluations) {
        boolean ownIsEvaluation = evaluations.contains(booking.getServiceId());
        TimeRange own = booking.range();
        return others.stream()
                .filter(other -> own.overlaps(other.range()))
                .filter(other -> ownIsEvaluation || evaluations.contains(other.serviceId()))
                .map(other -> new RoomOverlap(
                        StudioTime.inStudioZone(max(own.start(), other.start())),
                        StudioTime.inStudioZone(min(own.end(), other.end()))))
                .sorted(Comparator.comparing(RoomOverlap::start))
                .toList();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    // The order matters (guide 20, 3.5): the state machine first, so a refused step never reaches Stripe;
    // then the capture, whose failure rolls the transaction back and leaves PENDING_APPROVAL
    // Check, capture, and only then change (guide 33, part 3, D-063): a forbidden step never reaches Stripe, and a
    // refused capture leaves the booking exactly as it was, so the caller decides without throwing the
    // transaction away. false: Stripe refused the capture for good. Stripe not answering throws: all rolls back
    // How it is paid decides (guide 38, part 7): a card is captured now, a credit was committed at the request.
    // A switch EXPRESSION on the enum: the compiler demands every value, so a new way of paying does not compile
    // until someone decides what an approval does with it
    private boolean confirmPaid(Booking booking, Instant now) {
        booking.checkMove(BookingStatus.CONFIRMED);
        boolean paid = switch (booking.getPaidWith()) {
            case CARD -> cards.capture(booking.getId(), booking.getPriceCents())
                    instanceof CardHolds.CaptureResult.Captured;
            case CREDIT -> true;
            // born CONFIRMED from the panel: it never waits for an approval, so this is a bug
            case IN_STUDIO -> throw new IllegalStateException("Booking " + booking.getId()
                    + " is paid in the studio and cannot be waiting for an approval");
        };
        if (paid) {
            booking.approve(now);
            events.publishEvent(BookingConfirmed.of(booking));
        }
        return paid;
    }

    // Rejected, unanswered or withdrawn (D-030): the hold on the card is released, or the credit goes back to its
    // lot (guide 38, part 7). Neither throws: the release of a card is retried by the job if Stripe does not
    // answer (D-064), the credit is written in this same transaction
    private void releasePayment(Booking booking) {
        switch (booking.getPaidWith()) {
            case CARD -> cards.cancel(booking.getId());
            case CREDIT -> credits.release(booking.getId());
            case IN_STUDIO -> throw new IllegalStateException("Booking " + booking.getId()
                    + " is paid in the studio and cannot be waiting for an approval");
        }
    }

    // The request is already void after the deadline (D-030): the job of piece 4 closes it within a minute.
    // Checked only for the practitioner's answers: automatic approval happens at once
    private static void requireBeforeDeadline(Booking booking, Instant now) {
        if (booking.getApprovalDeadline() != null && !now.isBefore(booking.getApprovalDeadline())) {
            throw new BusinessRuleException(ErrorCode.APPROVAL_DEADLINE_PASSED,
                    "Booking " + booking.getId() + " had to be answered by " + booking.getApprovalDeadline());
        }
    }

    private Booking lockedForPractitioner(long practitionerId, long bookingId) {
        return bookings.findLockedByIdAndPractitionerId(bookingId, practitionerId)
                .orElseThrow(() -> notFound(bookingId));
    }

    private static NotFoundException notFound(long bookingId) {
        return new NotFoundException("Booking " + bookingId + " not found");
    }

    // Another customer took the slot between our check and our insert (guide 18, 3.3): for the customer it
    // is the same answer the check would have given. The constraint name belongs to this module, so the
    // translation is here and not in ApiExceptionHandler (guide 19, part 6). Two answers of the database mean it:
    // - 23P01 on booking_no_overlap_ex: the other insert committed first, ours waited and then lost;
    // - 40P01, deadlock: both inserts were in flight. An exclusion constraint puts the new row in its index
    //   BEFORE looking for conflicts, so each insert saw the other's uncommitted row and waited for it, and
    //   PostgreSQL killed one of the two after deadlock_timeout. The other goes on and keeps the slot.
    //   Our insert can wait on nothing else: the customer row is locked by us, and the foreign key checks
    //   (key share) do not conflict with the settings updates (no key update) (passi/07, "Errori", 5)
    // Any other failure is a bug and goes on as it is
    private static RuntimeException translateOverlap(DataAccessException e, long practitionerId, TimeRange slot) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // the PostgreSQL driver is a runtime dependency: its classes cannot be named, java.sql can
            if (cause instanceof SQLException sql && (isOverlapViolation(sql) || SQL_STATE_DEADLOCK.equals(sql.getSQLState()))) {
                return new BusinessRuleException(ErrorCode.SLOT_NOT_AVAILABLE,
                        "Slot " + slot.start() + " of practitioner " + practitionerId + " was just taken");
            }
        }
        return e;
    }

    private static boolean isOverlapViolation(SQLException sql) {
        return SQL_STATE_EXCLUSION_VIOLATION.equals(sql.getSQLState())
                && String.valueOf(sql.getMessage()).contains(NO_OVERLAP_CONSTRAINT);
    }
}
