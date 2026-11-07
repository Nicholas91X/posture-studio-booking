package com.posturestudio.booking.booking;

import java.time.Instant;

/**
 * Domain events: what happened to a booking, announced without knowing who listens (D-039).
 * Published inside the transaction with Spring's ApplicationEventPublisher; listeners (notifications,
 * step 8) use @TransactionalEventListener, which delivers them only after a successful commit (guide 20, part 4).
 *
 * sealed: these records are the only implementations, so a switch over a BookingEvent can list every
 * case with no default, and stops compiling when a new event is added (pattern matching, Java 21).
 * Ids and instants only, never the customer's name: listeners ask the module that owns it, and a push
 * notification on a locked screen must not show it (D-032). ApprovalExpired arrives with piece 4.
 */
public sealed interface BookingEvent
        permits BookingEvent.ApprovalRequested, BookingEvent.BookingConfirmed,
                BookingEvent.BookingRejected, BookingEvent.BookingWithdrawn {

    long bookingId();

    long practitionerId();

    long customerId();

    Instant startsAt();

    // paid, MANUAL mode: the practitioner must answer before approvalDeadline (push to the practitioner)
    record ApprovalRequested(long bookingId, long practitionerId, long customerId, Instant startsAt,
                             Instant approvalDeadline) implements BookingEvent {

        static ApprovalRequested of(Booking booking) {
            return new ApprovalRequested(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getStartsAt(), booking.getApprovalDeadline());
        }
    }

    // approved and captured, by the practitioner or automatically (email to the customer)
    record BookingConfirmed(long bookingId, long practitionerId, long customerId, Instant startsAt)
            implements BookingEvent {

        static BookingConfirmed of(Booking booking) {
            return new BookingConfirmed(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getStartsAt());
        }
    }

    // rejected by the practitioner, hold released (email to the customer)
    record BookingRejected(long bookingId, long practitionerId, long customerId, Instant startsAt)
            implements BookingEvent {

        static BookingRejected of(Booking booking) {
            return new BookingRejected(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getStartsAt());
        }
    }

    // withdrawn by the customer while waiting, hold released (push to the practitioner: one request less)
    record BookingWithdrawn(long bookingId, long practitionerId, long customerId, Instant startsAt)
            implements BookingEvent {

        static BookingWithdrawn of(Booking booking) {
            return new BookingWithdrawn(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getStartsAt());
        }
    }
}
