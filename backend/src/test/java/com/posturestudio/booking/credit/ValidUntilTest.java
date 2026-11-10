package com.posturestudio.booking.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.posturestudio.booking.shared.StudioTime;

// The rule of the last valid day (D-069, guide 36, part 8), without Spring. Every instant is written in UTC
// (Z) on purpose, with the time in Rome next to it: the traps are exactly where the two days differ.
// In 2026 the clock moves forward on Sunday 29 March (a day of 23 hours) and back on Sunday 25 October (25 hours)
class ValidUntilTest {

    @Test
    void validNDaysEndsOnTheDayOfThePurchasePlusNDays() {
        // Tuesday 10 November 2026, 18:00 in Rome: 90 days later is Monday 8 February 2027
        Instant purchase = Instant.parse("2026-11-10T17:00:00Z");

        assertThat(ValidUntil.daysAfter(purchase, 90).lastDay()).isEqualTo(LocalDate.of(2027, 2, 8));
    }

    // trap 1: the day of the purchase is the day in Rome, not in UTC
    @Test
    void theDayOfThePurchaseIsTheDayInRome() {
        // 23:30 UTC on 10 November is 00:30 on 11 November in Rome
        Instant justAfterMidnightInRome = Instant.parse("2026-11-10T23:30:00Z");

        assertThat(ValidUntil.daysAfter(justAfterMidnightInRome, 1).lastDay()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    // trap 2: N calendar days, not N times 24 hours
    @Test
    void daysAreDaysOfTheCalendarAcrossTheClockChange() {
        // 1 October 2026, 00:30 in Rome (summer time, +02:00); 30 days cross 25 October, a day of 25 hours
        Instant purchase = Instant.parse("2026-09-30T22:30:00Z");

        assertThat(ValidUntil.daysAfter(purchase, 30).lastDay()).isEqualTo(LocalDate.of(2026, 10, 31));
        // the wrong way, 30 x 24 hours, lands on 30 October at 23:30 in Rome: one day of validity lost
        LocalDate withHours = purchase.plus(Duration.ofHours(30 * 24)).atZone(StudioTime.ZONE).toLocalDate();
        assertThat(withHours).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void theLastDayIsUsableUntilMidnightInRome() {
        ValidUntil until = new ValidUntil(LocalDate.of(2026, 11, 20));

        // winter time, +01:00: 23:59:59 in Rome is 22:59:59 UTC
        assertThat(until.covers(Instant.parse("2026-11-20T22:59:59Z"))).isTrue();
        assertThat(until.covers(Instant.parse("2026-11-20T23:00:00Z"))).isFalse();
        assertThat(until.covers(Instant.parse("2026-11-01T08:00:00Z"))).isTrue();
    }

    // trap 3: the end of the day is not a fixed hour in UTC
    @Test
    void theEndOfTheLastDayFollowsTheClockForwardInMarch() {
        // 29 March 2026 ends at 24:00 summer time (+02:00), that is 22:00 UTC
        ValidUntil until = new ValidUntil(LocalDate.of(2026, 3, 29));

        assertThat(until.covers(Instant.parse("2026-03-29T21:59:59Z"))).isTrue();
        assertThat(until.covers(Instant.parse("2026-03-29T22:00:00Z"))).isFalse();
    }

    @Test
    void theEndOfTheLastDayFollowsTheClockBackInOctober() {
        // 25 October 2026 ends at 24:00 winter time (+01:00), that is 23:00 UTC
        ValidUntil until = new ValidUntil(LocalDate.of(2026, 10, 25));

        assertThat(until.covers(Instant.parse("2026-10-25T22:59:59Z"))).isTrue();
        assertThat(until.covers(Instant.parse("2026-10-25T23:00:00Z"))).isFalse();
    }

    @Test
    void validityIsAtLeastOneDay() {
        assertThatThrownBy(() -> ValidUntil.daysAfter(Instant.parse("2026-11-10T17:00:00Z"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLaterLastDayIsAfter() {
        ValidUntil march = new ValidUntil(LocalDate.of(2027, 3, 15));
        ValidUntil april = new ValidUntil(LocalDate.of(2027, 4, 30));

        assertThat(april.isAfter(march)).isTrue();
        assertThat(march.isAfter(april)).isFalse();
        assertThat(march.isAfter(march)).isFalse();
    }
}
