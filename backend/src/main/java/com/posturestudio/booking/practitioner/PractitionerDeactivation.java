package com.posturestudio.booking.practitioner;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * The deactivation of a practitioner by the admin, with its checks (D-077, guide 45). A service of its own, next to
 * PractitionerService: it needs the two modules that depend on practitioner (through FutureBookings and
 * OutstandingCredits), and PractitionerService stays usable without them.
 *
 * The race with a new booking (guide 45, part 4): the row of the practitioner is locked FOR UPDATE before counting.
 * Every new booking takes FOR KEY SHARE on the same row as its first step, and only then asks whether the
 * practitioner is active: the two conflict, so one waits for the other, and in READ COMMITTED whoever goes on
 * second reads what the first committed. A booking in flight makes this wait, then is counted (409); a deactivation
 * in flight makes the booking wait, then the booking finds the practitioner deactivated (404).
 */
@Service
public class PractitionerDeactivation {

    private final PractitionerRepository repository;
    private final PractitionerService practitioners;
    private final FutureBookings bookings;
    private final OutstandingCredits credits;
    private final Clock clock;

    PractitionerDeactivation(PractitionerRepository repository, PractitionerService practitioners,
                             FutureBookings bookings, OutstandingCredits credits, Clock clock) {
        this.repository = repository;
        this.practitioners = practitioners;
        this.bookings = bookings;
        this.credits = credits;
        this.clock = clock;
    }

    // Idempotent as before (D-052): deactivating again repeats the checks and the warning. A practitioner deactivated
    // before step 11 with future sessions gets a 409 now: the right thing for the admin to know
    @Transactional
    public PractitionerDeactivationView deactivate(long practitionerId) {
        // FIRST the lock, then the count: a count before the lock could already be old
        repository.lockForDeactivation(practitionerId)
                .orElseThrow(() -> new NotFoundException("Practitioner " + practitionerId + " not found"));
        long unfinished = bookings.unfinishedOf(practitionerId, clock.instant());
        if (unfinished > 0) {
            throw new BusinessRuleException(ErrorCode.PRACTITIONER_HAS_FUTURE_BOOKINGS,
                    "Practitioner " + practitionerId + " has " + unfinished + " sessions to come: cancel them first");
        }
        AdminPractitionerView practitioner = practitioners.deactivate(practitionerId);
        return new PractitionerDeactivationView(practitioner, credits.validOf(practitionerId));
    }
}
