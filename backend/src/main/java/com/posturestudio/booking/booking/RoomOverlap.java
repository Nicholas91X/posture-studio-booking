package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

/**
 * One room, two practitioners (D-043): when this booking overlaps a booking of the other practitioner
 * and one of the two is an evaluation. Only the shared interval, in Rome time: never who, never what
 * else (requirements, "Riservatezza del calendario"). The name of the field that holds it says "evaluation".
 */
public record RoomOverlap(OffsetDateTime start, OffsetDateTime end) {
}
