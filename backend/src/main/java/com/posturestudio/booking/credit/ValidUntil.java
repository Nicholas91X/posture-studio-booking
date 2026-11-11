package com.posturestudio.booking.credit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.posturestudio.booking.shared.Checks;
import com.posturestudio.booking.shared.StudioTime;

/**
 * The last day the credits of a lot can be used: a calendar day in Europe/Rome (D-069, guide 36, part 8).
 * "Expires on 15 March" means: usable on 15 March until midnight in Rome, not on the 16th.
 * "Valid N days" means: the last day is the day of the purchase, in Rome, plus N calendar days.
 * <p>
 * The one place of the rule. A date and not an instant: a day of the calendar has no hours, so there is no
 * midnight to compute and no change of the clock to get wrong. The three traps it avoids (guide 36, 8.4):
 * the day taken in UTC instead of Rome, N times 24 hours instead of N days (a day of the clock change lasts
 * 23 or 25 hours), and the end of the day fixed at an hour in UTC.
 */
public record ValidUntil(LocalDate lastDay) {

    public ValidUntil {
        Objects.requireNonNull(lastDay, "lastDay must not be null");
    }

    // "valid N days from this moment": calendar days, counted from the day of the moment in Rome
    public static ValidUntil daysAfter(Instant moment, int days) {
        Objects.requireNonNull(moment, "moment must not be null");
        Checks.requirePositive(days, "days");
        return new ValidUntil(moment.atZone(StudioTime.ZONE).toLocalDate().plusDays(days));
    }

    // whether the credits can be used at this moment: its day in Rome is not after the last valid day
    public boolean covers(Instant moment) {
        Objects.requireNonNull(moment, "moment must not be null");
        return !dayOf(moment).isAfter(lastDay);
    }

    // the day of a moment in Rome: what covers compares, and what a query compares valid_until with
    public static LocalDate dayOf(Instant moment) {
        return moment.atZone(StudioTime.ZONE).toLocalDate();
    }

    public boolean isAfter(ValidUntil other) {
        return lastDay.isAfter(other.lastDay);
    }
}
