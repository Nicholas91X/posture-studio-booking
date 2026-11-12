package com.posturestudio.booking.credit;

import java.time.LocalDate;

/**
 * The credit that came back to a booking (guide 43, parts 4 and 9): the lot it is in, and its last valid day in
 * Rome. A cancellation, or a rejection before that (D-030): whatever the reason, one at most per booking (V15, V16).
 */
public record ReturnedCredit(long lotId, LocalDate validUntil) {
}
