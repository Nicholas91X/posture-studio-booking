package com.posturestudio.booking.booking;

import java.time.Instant;

/**
 * Time taken in a practitioner's agenda (D-091): whose, from, to. Nothing else, not even the booking's id: the
 * query that builds it reads only these three columns, so no customer, service or state can leak (privacy by
 * construction, as BookedRange, D-049). Public only because Hibernate builds it with a constructor expression.
 */
public record BusyRange(long practitionerId, Instant start, Instant end) {
}
