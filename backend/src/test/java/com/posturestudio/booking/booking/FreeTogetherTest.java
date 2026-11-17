package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.posturestudio.booking.shared.TimeRange;

// "Free together" at its edges (D-091): a pure function, no Spring. Monday 9 November 2026, times in UTC for brevity
// (the function knows no zone: the hours come already converted by the schedule)
class FreeTogetherTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Instant EARLY = at("00:00");

    @Test
    void aWindowExactlyAsLongAsTheDurationCounts() {
        var anna = agenda(List.of(range("09:00", "10:00")), List.of());

        assertThat(FreeTogether.compute(List.of(anna), HOUR, EARLY)).containsExactly(range("09:00", "10:00"));
    }

    @Test
    void aWindowOneMinuteShorterDoesNot() {
        var anna = agenda(List.of(range("09:00", "09:59")), List.of());

        assertThat(FreeTogether.compute(List.of(anna), HOUR, EARLY)).isEmpty();
    }

    // half-open intervals: a session ending at 10:00 leaves 10:00 free, one starting at 11:00 leaves 10:00-11:00
    @Test
    void sessionsThatTouchTheWindowLeaveItWhole() {
        var anna = agenda(List.of(range("09:00", "12:00")), List.of(range("09:00", "10:00"), range("11:00", "12:00")));

        assertThat(FreeTogether.compute(List.of(anna), HOUR, EARLY)).containsExactly(range("10:00", "11:00"));
    }

    @Test
    void twoPractitionersAreFreeOnlyWhereBothAre() {
        var anna = agenda(List.of(range("09:00", "13:00")), List.of(range("10:00", "11:00")));
        var marco = agenda(List.of(range("08:00", "12:00")), List.of(range("11:30", "12:00")));

        assertThat(FreeTogether.compute(List.of(anna, marco), HOUR, EARLY))
                .containsExactly(range("09:00", "10:00"));
        // with half an hour, 11:00-11:30 too
        assertThat(FreeTogether.compute(List.of(anna, marco), Duration.ofMinutes(30), EARLY))
                .containsExactly(range("09:00", "10:00"), range("11:00", "11:30"));
    }

    // an absence takes the time as a booking does; overlapping takings are joined
    @Test
    void absencesAndBookingsTakeTimeAlike() {
        var anna = agenda(List.of(range("09:00", "13:00"), range("14:00", "19:00")),
                List.of(range("09:00", "10:30"), range("10:00", "12:00"), range("14:00", "19:00")));

        assertThat(FreeTogether.compute(List.of(anna), HOUR, EARLY)).containsExactly(range("12:00", "13:00"));
    }

    // never in the past: a window started already counts from now, and only if what is left is long enough
    @Test
    void nothingBeforeNow() {
        var anna = agenda(List.of(range("09:00", "11:00")), List.of());

        assertThat(FreeTogether.compute(List.of(anna), HOUR, at("09:30"))).containsExactly(range("09:30", "11:00"));
        assertThat(FreeTogether.compute(List.of(anna), HOUR, at("10:01"))).isEmpty();
        assertThat(FreeTogether.compute(List.of(anna), HOUR, at("10:00"))).containsExactly(range("10:00", "11:00"));
    }

    @Test
    void withoutWorkingHoursOrPractitionersNothingIsFree() {
        assertThat(FreeTogether.compute(List.of(agenda(List.of(), List.of())), HOUR, EARLY)).isEmpty();
        assertThat(FreeTogether.compute(List.of(), HOUR, EARLY)).isEmpty();
    }

    // bands touching each other are one window: 9-10 and 10-11 make a free 9-11
    @Test
    void touchingBandsMakeOneWindow() {
        var anna = agenda(List.of(range("10:00", "11:00"), range("09:00", "10:00")), List.of());

        assertThat(FreeTogether.compute(List.of(anna), Duration.ofMinutes(120), EARLY)).containsExactly(range("09:00", "11:00"));
    }

    private static FreeTogether.Agenda agenda(List<TimeRange> working, List<TimeRange> taken) {
        return new FreeTogether.Agenda(working, taken);
    }

    private static TimeRange range(String from, String to) {
        return new TimeRange(at(from), at(to));
    }

    private static Instant at(String time) {
        return Instant.parse("2026-11-09T" + time + ":00Z");
    }
}
