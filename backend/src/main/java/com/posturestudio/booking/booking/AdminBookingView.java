package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * A session to come of a practitioner, as the admin sees it before a deactivation (D-077, guide 45, part 5): what it
 * is and when, its state and how it is paid, so that the admin knows what to cancel. Never the customer's name: the
 * admin needs to know what, not who, and the customer is told by email anyway.
 */
public record AdminBookingView(long id, long serviceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
                               BookingStatus status, PaidWith paidWith) {

    static AdminBookingView from(Booking booking) {
        return new AdminBookingView(booking.getId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), booking.getPaidWith());
    }
}
