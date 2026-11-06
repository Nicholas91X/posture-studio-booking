package com.posturestudio.booking.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TimeRangeTest {

    // the reference range: 10:00-12:00 UTC
    private static final TimeRange TEN_TO_TWELVE = range("10:00", "12:00");

    // every case is checked in both directions: overlapping is symmetric, and a wrong formula
    // often shows up in one direction only
    @ParameterizedTest(name = "{0}-{1} overlaps 10-12: {2}")
    @CsvSource({
            "10:30, 11:30, true",   // inside
            "09:00, 13:00, true",   // around
            "09:00, 11:00, true",   // partly, on the left
            "11:00, 13:00, true",   // partly, on the right
            "10:00, 12:00, true",   // the same
            "08:00, 10:00, false",  // touching before: the end is excluded
            "12:00, 13:00, false",  // touching after
            "07:00, 08:00, false",  // far before
            "14:00, 15:00, false"   // far after
    })
    void overlapsIsTheSameInBothDirections(String start, String end, boolean expected) {
        TimeRange other = range(start, end);

        assertThat(TEN_TO_TWELVE.overlaps(other)).isEqualTo(expected);
        assertThat(other.overlaps(TEN_TO_TWELVE)).isEqualTo(expected);
    }

    @Test
    void endBeforeStartIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> range("12:00", "10:00"));
    }

    @Test
    void emptyRangeIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> range("10:00", "10:00"));
    }

    @Test
    void nullIsRejected() {
        Assertions.assertThrows(NullPointerException.class, () -> new TimeRange(null, Instant.now()));
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse("2026-11-09T" + start + ":00Z"), Instant.parse("2026-11-09T" + end + ":00Z"));
    }
}
