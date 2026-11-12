package com.posturestudio.booking.credit;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of the history of a lot (guide 41, part 3): a movement of the ledger or an extension, each with the
 * fields it has and the others null. at: in Rome. note: the why of the practitioner, only in the panel; the
 * customer never receives it (D-073)
 */
record CreditEntry(Kind kind, OffsetDateTime at, @Schema(nullable = true) Integer amount, @Schema(nullable = true) Long bookingId, @Schema(nullable = true) LotSource source,
                   @Schema(nullable = true) LocalDate oldValidUntil, @Schema(nullable = true) LocalDate newValidUntil, @Schema(nullable = true) String note) {

    // the reasons of the movements (same names) plus the extension
    enum Kind {
        LOT_CREATED, BOOKING, BOOKING_RELEASE, ADJUSTMENT, CANCELLATION_RESTORE, EXTENSION
    }
}
