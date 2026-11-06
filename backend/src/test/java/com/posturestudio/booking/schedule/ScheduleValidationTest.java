package com.posturestudio.booking.schedule;

import static com.posturestudio.booking.schedule.WorkingScheduleTest.band;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// The custom constraints of the module (OnTheHalfHour, ValidBands, EndsAfterStart), without Spring.
// What matters most is the path of each error: it tells the panel which field to fix
public class ScheduleValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    // ***** OnTheHalfHour *****

    @Test
    void timesOnTheHourAndOnTheHalfHourPass() {
        assertThat(paths(new WorkingBand(MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 30)))).isEmpty();
    }

    @Test
    void timeAtAQuarterPastFailsOnItsField() {
        assertThat(paths(new WorkingBand(MONDAY, LocalTime.of(9, 15), LocalTime.of(13, 0))))
                .containsExactly("startTime");
    }

    @Test
    void timeWithSecondsFails() {
        assertThat(paths(new WorkingBand(MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0, 30))))
                .containsExactly("endTime");
    }

    // ***** ValidBands *****

    @Test
    void validVersionPasses() {
        assertThat(paths(version(
                band(MONDAY, "09:00", "13:00"),
                band(MONDAY, "13:00", "19:00"),
                band(TUESDAY, "09:00", "13:00")))).isEmpty();
    }

    @Test
    void overlapIsReportedOnTheLaterBand() {
        assertThat(paths(version(
                band(MONDAY, "09:00", "13:00"),
                band(TUESDAY, "09:00", "13:00"),
                band(MONDAY, "12:00", "15:00")))).containsExactly("bands[2]");
    }

    @Test
    void bandEndingBeforeItStartsIsReportedOnItself() {
        assertThat(paths(version(
                band(MONDAY, "09:00", "13:00"),
                band(TUESDAY, "13:00", "09:00")))).containsExactly("bands[1]");
    }

    @Test
    void timeOfABandInTheListIsReportedWithItsFullPath() {
        assertThat(paths(version(new WorkingBand(MONDAY, LocalTime.of(9, 15), LocalTime.of(13, 0)))))
                .containsExactly("bands[0].startTime");
    }

    @Test
    void missingTimeIsLeftToNotNull() {
        assertThat(paths(version(new WorkingBand(MONDAY, null, LocalTime.of(13, 0)))))
                .containsExactly("bands[0].startTime");
    }

    // ***** EndsAfterStart *****

    @Test
    void absenceEndingAfterItStartsPasses() {
        assertThat(paths(new NewUnavailability(
                OffsetDateTime.parse("2026-11-12T11:15:00+01:00"),
                OffsetDateTime.parse("2026-11-12T12:00:00+01:00"), null))).isEmpty();
    }

    @Test
    void absenceEndingWhenItStartsFailsOnEndsAt() {
        assertThat(paths(new NewUnavailability(
                OffsetDateTime.parse("2026-11-12T11:15:00+01:00"),
                OffsetDateTime.parse("2026-11-12T11:15:00+01:00"), null))).containsExactly("endsAt");
    }

    @Test
    void sameInstantWithDifferentOffsetsIsNotAfter() {
        // 11:15 in Rome is 10:15 UTC: the same instant, so the end is not after the start
        assertThat(paths(new NewUnavailability(
                OffsetDateTime.parse("2026-11-12T11:15:00+01:00"),
                OffsetDateTime.parse("2026-11-12T10:15:00Z"), null))).containsExactly("endsAt");
    }

    @Test
    void reasonLongerThan200CharactersFails() {
        assertThat(paths(new NewUnavailability(
                OffsetDateTime.parse("2026-11-12T11:15:00+01:00"),
                OffsetDateTime.parse("2026-11-12T12:00:00+01:00"), "x".repeat(201)))).containsExactly("reason");
    }

    // ***** helpers *****

    private static ScheduleVersionRequest version(WorkingBand... bands) {
        return new ScheduleVersionRequest(LocalDate.of(2026, 11, 9), Arrays.asList(bands));
    }

    private static <T> List<String> paths(T object) {
        return validator.validate(object).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .sorted()
                .toList();
    }
}
