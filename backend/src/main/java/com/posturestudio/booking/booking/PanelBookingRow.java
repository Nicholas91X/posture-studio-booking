package com.posturestudio.booking.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import com.posturestudio.booking.customer.CustomerName;
import com.posturestudio.booking.shared.StudioTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A row of the practitioner's lists (guide 23), times in Rome. The customer is one of theirs, so the
 * name is shown. Two flags, computed only for bookings that hold the time now:
 * - outsideWorkingHours: no longer inside the working hours valid on its day, e.g. after a new
 *   schedule version (D-034). The booking stays valid;
 * - evaluationOverlaps: the intervals shared with the other practitioner's bookings when one of the two
 *   is an evaluation (D-043). Only the times.
 */
public record PanelBookingRow(long id, long customerId, @Schema(nullable = true) String customerFirstName, @Schema(nullable = true) String customerLastName,
                              long serviceId, OffsetDateTime startsAt, OffsetDateTime endsAt, BookingStatus status,
                              BookedBy bookedBy, PaidWith paidWith, @Schema(nullable = true) OffsetDateTime approvalDeadline,
                              boolean outsideWorkingHours, List<RoomOverlap> evaluationOverlaps) {

    static PanelBookingRow from(Booking booking, CustomerName customer, boolean outsideWorkingHours,
                                List<RoomOverlap> evaluationOverlaps) {
        return new PanelBookingRow(booking.getId(), booking.getCustomerId(),
                customer == null ? null : customer.firstName(), customer == null ? null : customer.lastName(),
                booking.getServiceId(), StudioTime.inStudioZone(booking.getStartsAt()),
                StudioTime.inStudioZone(booking.getEndsAt()), booking.getStatus(), booking.getBookedBy(),
                booking.getPaidWith(), inRome(booking.getApprovalDeadline()), outsideWorkingHours,
                List.copyOf(evaluationOverlaps));
    }

    private static OffsetDateTime inRome(Instant instant) {
        return instant == null ? null : StudioTime.inStudioZone(instant);
    }
}
