package com.posturestudio.booking.booking;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.posturestudio.booking.shared.StudioTime;

/**
 * A booking as its practitioner sees it after an answer, times in Rome (D-035).
 * The minimum for now: the customer's name and the overlap flags arrive with the lists of piece 6.
 */
public record PanelBookingView(long id, long customerId, long serviceId, OffsetDateTime startsAt,
                               OffsetDateTime endsAt, BookingStatus status, OffsetDateTime approvalDeadline) {

    static PanelBookingView from(Booking booking) {
        return new PanelBookingView(booking.getId(), booking.getCustomerId(), booking.getServiceId(),
                StudioTime.inStudioZone(booking.getStartsAt()), StudioTime.inStudioZone(booking.getEndsAt()),
                booking.getStatus(), inRome(booking.getApprovalDeadline()));
    }

    private static OffsetDateTime inRome(Instant instant) {
        return instant == null ? null : StudioTime.inStudioZone(instant);
    }
}
