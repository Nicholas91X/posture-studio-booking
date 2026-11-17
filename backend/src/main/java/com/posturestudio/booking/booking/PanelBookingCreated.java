package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to a booking from the panel (D-048): the same fields as PanelBookingView, flat, plus
 * customerCreated (D-088). True when the email typed was nobody's yet: the panel asks the practitioner to check
 * it, because a typo makes a new customer. Flat rather than nested, so the answer keeps the shape it had.
 */
public record PanelBookingCreated(long id, long customerId, long serviceId, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, BookingStatus status, PaidWith paidWith,
                                  @Schema(nullable = true) OffsetDateTime approvalDeadline,
                                  boolean customerCreated) {

    static PanelBookingCreated of(PanelBookingView view, boolean customerCreated) {
        return new PanelBookingCreated(view.id(), view.customerId(), view.serviceId(), view.startsAt(), view.endsAt(),
                view.status(), view.paidWith(), view.approvalDeadline(), customerCreated);
    }
}
