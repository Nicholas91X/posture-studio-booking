package com.posturestudio.booking.booking;

import java.time.Instant;

/**
 * Domain events: what happened to a booking, announced without knowing who listens (D-039).
 * Published inside the transaction with Spring's ApplicationEventPublisher; listeners use
 * @TransactionalEventListener, which delivers them only after a successful commit (guide 20, part 4).
 * The emails: notification.BookingNotifications (guide 27); booking does not know it exists.
 *
 * sealed: these records are the only implementations, so a switch over a BookingEvent can list every
 * case with no default, and stops compiling when a new event is added (pattern matching, Java 21).
 * Ids and instants only, never the customer's name: listeners ask the module that owns it, and a push
 * notification on a locked screen must not show it (D-032). Plus, from step 10, how the booking is paid
 * (paidWith): a fact of the moment, which a listener after the commit should not reconstruct, and which
 * changes what the emails say, a card hold or a credit (guide 38, part 8).
 */
public sealed interface BookingEvent
        permits BookingEvent.ApprovalRequested, BookingEvent.BookingConfirmed, BookingEvent.BookingRejected,
                BookingEvent.BookingWithdrawn, BookingEvent.ApprovalExpired, BookingEvent.LatePaymentReleased {

    long bookingId();

    long practitionerId();

    long customerId();

    // which service: the emails name it (guide 27, 5.4). An id like the others, so still "ids and instants"
    long serviceId();

    Instant startsAt();

    // paid, MANUAL mode: the practitioner must answer before approvalDeadline (email to both)
    record ApprovalRequested(long bookingId, long practitionerId, long customerId, long serviceId,
                             Instant startsAt, Instant approvalDeadline, PaidWith paidWith)
implements BookingEvent {

        static ApprovalRequested of(Booking booking) {
            return new ApprovalRequested(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt(), booking.getApprovalDeadline(),
                    booking.getPaidWith());
        }
    }

    // approved and captured (by the practitioner or automatically), or booked from the panel (email to the customer)
    record BookingConfirmed(long bookingId, long practitionerId, long customerId, long serviceId, Instant startsAt,
                           PaidWith paidWith)
implements BookingEvent {

        static BookingConfirmed of(Booking booking) {
            return new BookingConfirmed(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt(), booking.getPaidWith());
        }
    }

    // rejected by the practitioner, hold released (email to the customer)
    record BookingRejected(long bookingId, long practitionerId, long customerId, long serviceId, Instant startsAt,
                           PaidWith paidWith)
implements BookingEvent {

        static BookingRejected of(Booking booking) {
            return new BookingRejected(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt(), booking.getPaidWith());
        }
    }

    // nobody answered before the deadline, hold released by the job (email to the customer)
    record ApprovalExpired(long bookingId, long practitionerId, long customerId, long serviceId, Instant startsAt,
                           PaidWith paidWith)
implements BookingEvent {

        static ApprovalExpired of(Booking booking) {
            return new ApprovalExpired(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt(), booking.getPaidWith());
        }
    }

    // paid after the booking had expired: this payment is released by the webhook (email to the customer,
    // guide 32, part 9). Published only after the release: if it fails, everything rolls back, event included
    record LatePaymentReleased(long bookingId, long practitionerId, long customerId, long serviceId, Instant startsAt)
            implements BookingEvent {

        static LatePaymentReleased of(Booking booking) {
            return new LatePaymentReleased(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt());
        }
    }

    // withdrawn by the customer while waiting, hold released (email to the customer)
    record BookingWithdrawn(long bookingId, long practitionerId, long customerId, long serviceId, Instant startsAt,
                           PaidWith paidWith)
implements BookingEvent {

        static BookingWithdrawn of(Booking booking) {
            return new BookingWithdrawn(booking.getId(), booking.getPractitionerId(), booking.getCustomerId(),
                    booking.getServiceId(), booking.getStartsAt(), booking.getPaidWith());
        }
    }
}
