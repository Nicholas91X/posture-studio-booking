package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * A booking as its customer sees it, times in Rome (D-035). holdExpiresAt is what the payment page
 * counts down to while the status is PENDING_PAYMENT; afterwards it is history.
 */
public record CustomerBookingView(long id, long practitionerId, long serviceId, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, BookingStatus status, long priceCents,
                                  OffsetDateTime holdExpiresAt) {

    static CustomerBookingView from(Booking booking) {
        return new CustomerBookingView(booking.getId(), booking.getPractitionerId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), booking.getPriceCents(),
                booking.getHoldExpiresAt() == null ? null : StudioTime.inStudioZone(booking.getHoldExpiresAt()));
    }
}
