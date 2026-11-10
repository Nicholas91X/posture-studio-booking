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
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.ServiceOnSale;
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
 * Dependencies: booking -> customer, catalog, practitioner, schedule. Nobody depends on booking:
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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BookingService(BookingRepository bookings, CatalogService catalog, CustomerService customers,
                   PractitionerService practitioners, ScheduleService schedule, CardHolds cards,
                   CardCheckout checkout, ApplicationEventPublisher events, Clock clock) {
        this.bookings = bookings;
        this.catalog = catalog;
        this.customers = customers;
        this.practitioners = practitioners;
        this.schedule = schedule;
        this.cards = cards;
        this.checkout = checkout;
        this.events = events;
        this.clock = clock;
    }

    // ***** CUSTOMER *****

    // A customer asks for a slot (guide 19, part 2 for the order). One transaction, one reading of the clock.
    // Outcome: PENDING_PAYMENT, held for 32 minutes (D-008, D-061), with the page where the customer pays
    // (guide 31), until paymentAuthorized
    @Transactional
    public CustomerBookingView request(long customerId, NewBooking request) {
        Instant now = clock.instant();
        long practitionerId = request.practitionerId();
        Instant start = request.startsAt().toInstant();

        // 1. the request names something on sale (404): no lock needed for that
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, request.serviceId());

        // 1b. nobody can pay this practitioner by card (no payment account): refused before any lock or
        // write, so no slot is ever held for a booking nobody can pay (guide 30, part 6)
        if (!checkout.available(practitionerId)) {
            throw new BusinessRuleException(ErrorCode.CARD_PAYMENTS_UNAVAILABLE,
                    "Practitioner " + practitionerId + " cannot take card payments yet");
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

        // 6. the insert, flushed here and not at commit, so that a violation can be translated
        Booking booking = Booking.requestedByCustomer(customerId, practitionerId, service.id(), slot,
                service.priceCents(), now);
        try {
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw translateOverlap(e, practitionerId, slot);
        }

        // 7. the Stripe page, inside this transaction (guide 31, parts 3 and 4): if Stripe fails, the booking
        // is rolled back with everything else; if our commit fails after Stripe answered, a session nobody
        // knows expires by itself. The page expires with the hold, never after it
        URI page = checkout.start(new CardCheckout.CheckoutRequest(booking.getId(), practitionerId, customerId,
                service.id(), booking.getPriceCents(), booking.getHoldExpiresAt()));
        return CustomerBookingView.from(booking, page);
    }

    @Transactional(readOnly = true)
    public CustomerBookingView findForCustomer(long customerId, long bookingId) {
        Booking booking = bookings.findByIdAndCustomerId(bookingId, customerId).orElseThrow(() -> notFound(bookingId));
        return customerView(booking, clock.instant());
    }

    // While waiting for the answer the customer can give up, nothing charged (D-030). Allowed even after
    // the deadline: the outcome for the customer is the same as the expiry the job is about to do
    @Transactional
    public CustomerBookingView withdraw(long customerId, long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedByIdAndCustomerId(bookingId, customerId)
                .orElseThrow(() -> notFound(bookingId));
        booking.withdraw(now);
        cards.cancel(booking.getId());
        events.publishEvent(BookingWithdrawn.of(booking));
        return CustomerBookingView.from(booking);
    }

    // ***** PAYMENT *****

    // The card hold is in place. Called by the simulated payment now, by the Stripe webhook at step 9.
    // One path for both approval modes (D-030): PENDING_APPROVAL first, then AUTOMATIC approves at once
    // with the same confirm() a practitioner uses, inside the same transaction
    @Transactional
    public CustomerBookingView paymentAuthorized(long bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findLockedById(bookingId).orElseThrow(() -> notFound(bookingId));

        // paid too late: the booking expired and its slot may be someone else's. Release the money,
        // change nothing, no error: at step 9 the webhook must answer "received" (D-031, guide 20, 6.3).
        // A PENDING_PAYMENT whose hold expired is still accepted: nobody took the slot, or the lazy
        // expiry would have made it EXPIRED
        if (booking.getStatus() == BookingStatus.EXPIRED) {
            cards.cancel(booking.getId());
            return CustomerBookingView.from(booking);
        }

        PractitionerSettings settings = practitioners.getSettings(booking.getPractitionerId());
        booking.paymentAuthorized(Duration.ofHours(settings.approvalWindowHours()));
        switch (settings.bookingApproval()) {
            case AUTOMATIC -> confirm(booking, now);
            case MANUAL -> events.publishEvent(ApprovalRequested.of(booking));
        }
        return CustomerBookingView.from(booking);
    }

    // ***** PRACTITIONER *****

    // Booked from the panel for a customer, paid in the studio and confirmed at once (guide 22).
    // Working bands and absences apply; grid, notice, horizon, limit and approval do not (guide 22, part 2).
    // Customer found or created by email in this same transaction: a failed booking leaves no new customer
    @Transactional
    public PanelBookingView bookFromPanel(long practitionerId, NewPanelBooking request) {
        Instant now = clock.instant();
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
        Booking booking = Booking.bookedFromPanel(customerId, practitionerId, service.id(), slot,
                service.priceCents(), now);
        try {
            bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw translateOverlap(e, practitionerId, slot);
        }
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
        confirm(booking, now);
        return PanelBookingView.from(booking);
    }

    @Transactional
    public PanelBookingView reject(long practitionerId, long bookingId) {
        Instant now = clock.instant();
        Booking booking = lockedForPractitioner(practitionerId, bookingId);
        requireBeforeDeadline(booking, now);
        booking.reject(now);
        cards.cancel(booking.getId());
        events.publishEvent(BookingRejected.of(booking));
        return PanelBookingView.from(booking);
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

    @Transactional(readOnly = true)
    public List<CustomerBookingView> listForCustomer(long customerId) {
        Instant now = clock.instant();
        return bookings.findByCustomerIdOrderByStartsAtDesc(customerId).stream()
                .map(booking -> customerView(booking, now))
                .toList();
    }

    // ***** JOBS (guide 21) *****

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
        cards.cancel(booking.getId());
        events.publishEvent(ApprovalExpired.of(booking));
    }

    // ***** HELPERS *****

    // The payment page only while the slot is still held for the customer (guide 31, part 9). At most a few
    // such bookings per customer (the limit of future bookings), so one question each is fine
    private CustomerBookingView customerView(Booking booking, Instant now) {
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT && booking.holdsTimeAt(now)) {
            return CustomerBookingView.from(booking, checkout.pageOf(booking.getId()).orElse(null));
        }
        return CustomerBookingView.from(booking);
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
    private void confirm(Booking booking, Instant now) {
        booking.approve(now);
        cards.capture(booking.getId(), booking.getPriceCents());
        events.publishEvent(BookingConfirmed.of(booking));
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
