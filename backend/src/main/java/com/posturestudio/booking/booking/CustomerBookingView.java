package com.posturestudio.booking.booking;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * A booking as its customer sees it, times in Rome (D-035). holdExpiresAt is what the payment page
 * counts down to while the status is PENDING_PAYMENT; approvalDeadline is how long the customer may
 * wait for the answer while PENDING_APPROVAL (D-030). Afterwards both are history.
 */
public record CustomerBookingView(long id, long practitionerId, long serviceId, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, BookingStatus status, long priceCents,
                                  OffsetDateTime holdExpiresAt, OffsetDateTime approvalDeadline) {

    static CustomerBookingView from(Booking booking) {
        return new CustomerBookingView(booking.getId(), booking.getPractitionerId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), booking.getPriceCents(),
                inRome(booking.getHoldExpiresAt()), inRome(booking.getApprovalDeadline()));
    }

    private static OffsetDateTime inRome(Instant instant) {
        return instant == null ? null : StudioTime.inStudioZone(instant);
    }
}
