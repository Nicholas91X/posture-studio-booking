package com.posturestudio.booking.schedule;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: the aggregate keeps its own rules, without Spring or a database
public class WorkingScheduleTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 9);

    @Test
    void bandsAreKeptAndReturnedByDayThenStart() {
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of(
                band(TUESDAY, "09:00", "13:00"),
                band(MONDAY, "14:00", "19:00"),
                band(MONDAY, "09:00", "13:00")));

        assertThat(version.bands()).containsExactly(
                band(MONDAY, "09:00", "13:00"),
                band(MONDAY, "14:00", "19:00"),
                band(TUESDAY, "09:00", "13:00"));
    }

    @Test
    void overlappingBandsAreRejected() {
        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkingSchedule(1L, DATE, List.of(
                        band(MONDAY, "09:00", "13:00"),
                        band(MONDAY, "12:00", "15:00"))));
        assertThat(exception.getMessage()).contains("overlaps");
    }

    @Test
    void touchingBandsAreAccepted() {
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of(
                band(MONDAY, "09:00", "13:00"),
                band(MONDAY, "13:00", "14:00")));

        assertThat(version.bands()).hasSize(2);
    }

    @Test
    void sameBandOnTwoDaysIsAccepted() {
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of(
                band(MONDAY, "09:00", "13:00"),
                band(TUESDAY, "09:00", "13:00")));

        assertThat(version.bands()).hasSize(2);
    }

    @Test
    void bandEndingBeforeItStartsIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkingSchedule(1L, DATE, List.of(band(MONDAY, "13:00", "09:00"))));
    }

    @Test
    void bandAtAQuarterPastIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WorkingSchedule(1L, DATE, List.of(band(MONDAY, "09:15", "13:00"))));
    }

    @Test
    void invalidNewBandsLeaveTheVersionAsItWas() {
        // arrange
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of(band(MONDAY, "09:00", "13:00")));

        // act: the first new band is valid, the second overlaps the existing one
        Assertions.assertThrows(IllegalArgumentException.class, () -> version.addBands(List.of(
                band(TUESDAY, "09:00", "13:00"),
                band(MONDAY, "10:00", "11:00"))));

        // assert: not even the valid one was added
        assertThat(version.bands()).containsExactly(band(MONDAY, "09:00", "13:00"));
    }

    @Test
    void emptyVersionIsAllowed() {
        assertThat(new WorkingSchedule(1L, DATE, List.of()).bands()).isEmpty();
    }

    @Test
    void returnedBandsCannotBeModified() {
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of(band(MONDAY, "09:00", "13:00")));

        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> version.bands().add(band(TUESDAY, "09:00", "13:00")));
    }

    @Test
    void versionStartingTodayOrLaterIsEditable() {
        WorkingSchedule version = new WorkingSchedule(1L, DATE, List.of());

        assertThat(version.isEditableOn(DATE.minusDays(1))).isTrue();
        assertThat(version.isEditableOn(DATE)).isTrue();
        assertThat(version.isEditableOn(DATE.plusDays(1))).isFalse();
    }

    static WorkingBand band(DayOfWeek day, String start, String end) {
        return new WorkingBand(day, LocalTime.parse(start), LocalTime.parse(end));
    }
}
