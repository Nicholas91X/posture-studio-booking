package com.posturestudio.booking.booking;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.catalog.ServiceOnSale;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.practitioner.PractitionerSettings;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;
import com.posturestudio.booking.shared.TimeRange;

/**
 * The only door of the booking module (D-002): returns records, never the entity.
 * Every customer method receives the id of the customer who acts and finds only what is theirs.
 * Dependencies: booking -> customer, catalog, practitioner, schedule. Nobody depends on booking:
 * schedule reaches it only through OccupiedTime (D-044).
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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BookingService(BookingRepository bookings, CatalogService catalog, CustomerService customers,
                   PractitionerService practitioners, ScheduleService schedule, CardHolds cards,
                   ApplicationEventPublisher events, Clock clock) {
        this.bookings = bookings;
        this.catalog = catalog;
        this.customers = customers;
        this.practitioners = practitioners;
        this.schedule = schedule;
        this.cards = cards;
        this.events = events;
        this.clock = clock;
    }

    // ***** CUSTOMER *****

    // A customer asks for a slot (guide 19, part 2 for the order). One transaction, one reading of the clock.
    // Outcome: PENDING_PAYMENT, held for 30 minutes (D-008), until paymentAuthorized
    @Transactional
    public CustomerBookingView request(long customerId, NewBooking request) {
        Instant now = clock.instant();
        long practitionerId = request.practitionerId();
        Instant start = request.startsAt().toInstant();

        // 1. the request names something on sale (404): no lock needed for that
        ServiceOnSale service = catalog.serviceOnSale(practitionerId, request.serviceId());

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
        return CustomerBookingView.from(booking);
    }

    @Transactional(readOnly = true)
    public CustomerBookingView findForCustomer(long customerId, long bookingId) {
        return bookings.findByIdAndCustomerId(bookingId, customerId)
                .map(CustomerBookingView::from)
                .orElseThrow(() -> notFound(bookingId));
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

    // ***** HELPERS *****

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
