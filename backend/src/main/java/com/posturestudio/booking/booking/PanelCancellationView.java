package com.posturestudio.booking.booking;

import com.posturestudio.booking.credit.ReturnedCredit;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer of a cancellation by the studio (guide 44, part 3): the session as the panel sees it, and the credit
 * that came back to the customer (its lot and last valid day), or null when nothing did. The lot is the one the
 * practitioner sets to zero if the customer wants the money instead (D-075, docs/procedure-studio.md).
 * A record of its own and not a field of PanelBookingView: that view is the contract of every answer of the panel,
 * and the credit makes sense only here.
 */
public record PanelCancellationView(PanelBookingView booking, @Schema(nullable = true) ReturnedCredit returnedCredit) {
}
