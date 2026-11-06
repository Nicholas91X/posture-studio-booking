package com.posturestudio.booking.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * The time zone of the studio, in one place. Instants are stored in UTC; dates and times are
 * shown, and "today" is computed, in Europe/Rome (CLAUDE.md, "Date e ore").
 * "Now" always comes from the injected Clock, never from the system: tests fix it.
 */
public final class StudioTime {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private StudioTime() {
    }

    // today in Rome: between midnight and 1 am (2 am in summer) the UTC date is still yesterday
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }

    // an instant as the studio sees it, e.g. 2026-11-12T11:15+01:00 (D-035)
    public static OffsetDateTime inStudioZone(Instant instant) {
        return instant.atZone(ZONE).toOffsetDateTime();
    }
}
