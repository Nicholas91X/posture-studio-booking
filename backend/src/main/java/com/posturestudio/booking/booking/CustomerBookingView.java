package com.posturestudio.booking.booking;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.posturestudio.booking.credit.ReturnedCredit;
import com.posturestudio.booking.shared.StudioTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A booking as its customer sees it, times in Rome (D-035). holdExpiresAt is what the payment page
 * counts down to while the status is PENDING_PAYMENT; approvalDeadline is how long the customer may
 * wait for the answer while PENDING_APPROVAL (D-030). Afterwards both are history.
 * paymentUrl: the Stripe page where the customer pays, only while the slot is still held for them
 * (guide 31, part 9); null in every other case, a booking paid with a credit included (nothing to pay).
 * paidWith: CARD, CREDIT, or IN_STUDIO for a booking made from the panel (guide 38, part 8).
 * freeCancellationUntil: until when a cancellation is in time, that instant included, photographed at booking time
 * (V16): the frontend shows it, the backend decides at the click (D-005, guide 43, part 9).
 * cancelledAt: when it was withdrawn or cancelled. returnedCredit: the credit that came back to this booking (after
 * a rejection, a withdrawal, a cancellation in time), its lot and last valid day; null when nothing did.
 */
public record CustomerBookingView(long id, long practitionerId, long serviceId, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, BookingStatus status, PaidWith paidWith, long priceCents,
                                  @Schema(nullable = true) OffsetDateTime holdExpiresAt, @Schema(nullable = true) OffsetDateTime approvalDeadline, @Schema(nullable = true) URI paymentUrl,
                                  OffsetDateTime freeCancellationUntil, @Schema(nullable = true) OffsetDateTime cancelledAt,
                                  @Schema(nullable = true) ReturnedCredit returnedCredit) {

    static CustomerBookingView from(Booking booking) {
        return from(booking, null, null);
    }

    static CustomerBookingView from(Booking booking, URI paymentUrl) {
        return from(booking, paymentUrl, null);
    }

    static CustomerBookingView from(Booking booking, URI paymentUrl, ReturnedCredit returnedCredit) {
        return new CustomerBookingView(booking.getId(), booking.getPractitionerId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), booking.getPaidWith(), booking.getPriceCents(),
                inRome(booking.getHoldExpiresAt()), inRome(booking.getApprovalDeadline()), paymentUrl,
                inRome(booking.getFreeCancellationUntil()), inRome(booking.getCancelledAt()), returnedCredit);
    }

    private static OffsetDateTime inRome(Instant instant) {
        return instant == null ? null : StudioTime.inStudioZone(instant);
    }
}
