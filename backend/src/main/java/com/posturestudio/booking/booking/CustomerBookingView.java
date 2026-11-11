package com.posturestudio.booking.booking;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * A booking as its customer sees it, times in Rome (D-035). holdExpiresAt is what the payment page
 * counts down to while the status is PENDING_PAYMENT; approvalDeadline is how long the customer may
 * wait for the answer while PENDING_APPROVAL (D-030). Afterwards both are history.
 * paymentUrl: the Stripe page where the customer pays, only while the slot is still held for them
 * (guide 31, part 9); null in every other case, a booking paid with a credit included (nothing to pay).
 * paidWith: CARD, CREDIT, or IN_STUDIO for a booking made from the panel (guide 38, part 8).
 */
public record CustomerBookingView(long id, long practitionerId, long serviceId, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, BookingStatus status, PaidWith paidWith, long priceCents,
                                  OffsetDateTime holdExpiresAt, OffsetDateTime approvalDeadline, URI paymentUrl) {

    static CustomerBookingView from(Booking booking) {
        return from(booking, null);
    }

    static CustomerBookingView from(Booking booking, URI paymentUrl) {
        return new CustomerBookingView(booking.getId(), booking.getPractitionerId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), booking.getPaidWith(), booking.getPriceCents(),
                inRome(booking.getHoldExpiresAt()), inRome(booking.getApprovalDeadline()), paymentUrl);
    }

    private static OffsetDateTime inRome(Instant instant) {
        return instant == null ? null : StudioTime.inStudioZone(instant);
    }
}
