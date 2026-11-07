package com.posturestudio.booking.booking;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class BookingService {

    private static final String SQL_STATE_EXCLUSION_VIOLATION = "23P01";
    private static final String NO_OVERLAP_CONSTRAINT = "booking_no_overlap_ex";

    private final BookingRepository bookings;
    private final CatalogService catalog;
    private final CustomerService customers;
    private final PractitionerService practitioners;
    private final ScheduleService schedule;
    private final Clock clock;

    BookingService(BookingRepository bookings, CatalogService catalog, CustomerService customers,
                   PractitionerService practitioners, ScheduleService schedule, Clock clock) {
        this.bookings = bookings;
        this.catalog = catalog;
        this.customers = customers;
        this.practitioners = practitioners;
        this.schedule = schedule;
        this.clock = clock;
    }

    // ***** CUSTOMER *****

    // A customer asks for a slot (guide 19, part 2 for the order). One transaction, one reading of the clock.
    // Outcome: PENDING_PAYMENT, held for 30 minutes (D-008); payment arrives with piece 3
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
        } catch (DataIntegrityViolationException e) {
            throw translateOverlap(e, practitionerId, slot);
        }
        return CustomerBookingView.from(booking);
    }

    @Transactional(readOnly = true)
    public CustomerBookingView findForCustomer(long customerId, long bookingId) {
        return bookings.findByIdAndCustomerId(bookingId, customerId)
                .map(CustomerBookingView::from)
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found"));
    }

    // ***** HELPERS *****

    // Another customer took the slot between our check and our insert (guide 18, 3.3): for the customer it
    // is the same answer the check would have given. The constraint name belongs to this module, so the
    // translation is here and not in ApiExceptionHandler (guide 19, part 6). Any other violation is a bug
    private static RuntimeException translateOverlap(DataIntegrityViolationException e, long practitionerId, TimeRange slot) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            // the PostgreSQL driver is a runtime dependency: its classes cannot be named, java.sql can
            if (cause instanceof SQLException sql
                    && SQL_STATE_EXCLUSION_VIOLATION.equals(sql.getSQLState())
                    && String.valueOf(sql.getMessage()).contains(NO_OVERLAP_CONSTRAINT)) {
                return new BusinessRuleException(ErrorCode.SLOT_NOT_AVAILABLE,
                        "Slot " + slot.start() + " of practitioner " + practitionerId + " was just taken");
            }
        }
        return e;
    }
}
