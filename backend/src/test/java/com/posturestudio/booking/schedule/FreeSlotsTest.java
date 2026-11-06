package com.posturestudio.booking.schedule;

import static com.posturestudio.booking.schedule.WorkingScheduleTest.band;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.posturestudio.booking.shared.TimeRange;

// The free slot calculation, without Spring or a database (guide 16, part 7).
// Unless a test says otherwise, it is Friday 6 November 2026, 10:00 in Rome, and Anna works
// Monday to Friday 9-13 and 14-19 with half-hour starts, for a 60-minute session.
// Expected times are written by hand, with their offset (+01:00 in November, +02:00 in summer)
// or in UTC: never computed with the same conversion the code uses
public class FreeSlotsTest {

    private static final LocalDate MONDAY_9_NOVEMBER = LocalDate.of(2026, 11, 9);

    private static final List<WorkingBand> WEEKDAYS = everyDay(List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY),
            "09:00", "13:00", "14:00", "19:00");

    // ***** THE EXAMPLE OF GUIDE 13 *****

    @Test
    void annasMondayWithAnAbsenceFrom1115To1200() {
        List<TimeRange> slots = query()
                .busy(range("2026-11-09T11:15:00+01:00", "2026-11-09T12:00:00+01:00"))
                .compute();

        // 10:30, 11:00 and 11:30 overlap the absence; 10:00-11:00 ends before it; 12:00 starts when it ends
        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00:00+01:00", "2026-11-09T09:30:00+01:00", "2026-11-09T10:00:00+01:00",
                "2026-11-09T12:00:00+01:00",
                "2026-11-09T14:00:00+01:00", "2026-11-09T14:30:00+01:00", "2026-11-09T15:00:00+01:00",
                "2026-11-09T15:30:00+01:00", "2026-11-09T16:00:00+01:00", "2026-11-09T16:30:00+01:00",
                "2026-11-09T17:00:00+01:00", "2026-11-09T17:30:00+01:00", "2026-11-09T18:00:00+01:00"));
        assertThat(slots.getFirst().end()).isEqualTo(Instant.parse("2026-11-09T10:00:00+01:00"));
    }

    @Test
    void sameDayWithHourlyStarts() {
        List<TimeRange> slots = query()
                .step(60)
                .busy(range("2026-11-09T11:15:00+01:00", "2026-11-09T12:00:00+01:00"))
                .compute();

        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00:00+01:00", "2026-11-09T10:00:00+01:00", "2026-11-09T12:00:00+01:00",
                "2026-11-09T14:00:00+01:00", "2026-11-09T15:00:00+01:00", "2026-11-09T16:00:00+01:00",
                "2026-11-09T17:00:00+01:00", "2026-11-09T18:00:00+01:00"));
    }

    // ***** DURATION AND GRID *****

    @Test
    void thirtyMinuteSessionFitsUntil1230() {
        List<TimeRange> slots = query().versions(mondayOnly("09:00", "13:00")).duration(30).compute();

        assertThat(starts(slots)).hasSize(8)
                .endsWith(Instant.parse("2026-11-09T12:30:00+01:00"));
    }

    @Test
    void fortyFiveMinuteSessionLastStartsAt1200() {
        // 12:00-12:45 is inside the band, 12:30-13:15 is not; the grid is about starts, not ends
        List<TimeRange> slots = query().versions(mondayOnly("09:00", "13:00")).duration(45).compute();

        assertThat(starts(slots)).hasSize(7)
                .endsWith(Instant.parse("2026-11-09T12:00:00+01:00"));
    }

    @Test
    void sessionLongerThanEveryBandHasNoSlots() {
        assertThat(query().duration(6 * 60).compute()).isEmpty();
    }

    @Test
    void weekendHasNoSlots() {
        assertThat(query().days(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)).compute()).isEmpty();
    }

    // ***** BUSY RANGES *****

    @Test
    void absenceEndingExactlyAtAStartLeavesThatSlotFree() {
        List<TimeRange> slots = query()
                .versions(mondayOnly("09:00", "13:00"))
                .busy(range("2026-11-09T08:00:00+01:00", "2026-11-09T09:00:00+01:00"))
                .compute();

        assertThat(starts(slots).getFirst()).isEqualTo(Instant.parse("2026-11-09T09:00:00+01:00"));
    }

    @Test
    void absenceOverTheWholeDayLeavesNoSlots() {
        List<TimeRange> slots = query()
                .busy(range("2026-11-09T00:00:00+01:00", "2026-11-10T00:00:00+01:00"))
                .compute();

        assertThat(slots).isEmpty();
    }

    @Test
    void bookingIsExcludedLikeAnAbsence() {
        // from step 7 bookings arrive in the same list: the calculation does not know why a range is busy
        List<TimeRange> slots = query()
                .versions(mondayOnly("09:00", "13:00"))
                .busy(range("2026-11-09T09:00:00+01:00", "2026-11-09T10:00:00+01:00"))
                .compute();

        assertThat(starts(slots).getFirst()).isEqualTo(Instant.parse("2026-11-09T10:00:00+01:00"));
    }

    // ***** MINIMUM NOTICE (D-030) *****

    @ParameterizedTest(name = "now Monday 10:00, slot {0}: free {1}")
    @CsvSource({
            "2026-11-10T09:30:00+01:00, false",
            "2026-11-10T10:00:00+01:00, false",  // exactly 24 hours later: "more than" 24 hours is needed
            "2026-11-10T10:30:00+01:00, true"
    })
    void minimumNoticeIsMoreThan24Hours(String slot, boolean expected) {
        List<TimeRange> slots = query()
                .now("2026-11-09T09:00:00Z")   // Monday 9 November, 10:00 in Rome
                .days(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10))
                .compute();

        assertThat(starts(slots).contains(Instant.parse(slot))).isEqualTo(expected);
    }

    @Test
    void daysBeforeTodayAreIgnored() {
        assertThat(query().days(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5)).compute()).isEmpty();
    }

    // ***** HORIZON (D-023) *****

    @Test
    void lastDayOfTheHorizonIsIncludedAndTheNextIsNot() {
        // today 6 November + 21 days = Friday 27 November. Saturday 28 has a band too, but is beyond
        List<TimeRange> slots = query()
                .versions(everyDayFrom("09:00", "10:00"))
                .step(60)
                .days(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 28))
                .compute();

        assertThat(starts(slots)).containsExactlyElementsOf(at("2026-11-27T09:00:00+01:00"));
    }

    @Test
    void hugeRangeStopsAtTheHorizon() {
        // one slot a day; Saturday 7 at 9:00 is within 24 hours of now: from Sunday 8 to Friday 27
        List<TimeRange> slots = query()
                .versions(everyDayFrom("09:00", "10:00"))
                .step(60)
                .days(LocalDate.of(2026, 11, 6), LocalDate.of(2099, 12, 31))
                .compute();

        assertThat(slots).hasSize(20);
        assertThat(slots.getFirst().start()).isEqualTo(Instant.parse("2026-11-08T09:00:00+01:00"));
        assertThat(slots.getLast().start()).isEqualTo(Instant.parse("2026-11-27T09:00:00+01:00"));
    }

    // ***** DAYLIGHT SAVING TIME *****

    @Test
    void marchChangeKeepsNineInTheMorningInRome() {
        // Friday 27 March 2026 is still UTC+1, Monday 30 already UTC+2 (the change is on Sunday 29)
        List<TimeRange> slots = query()
                .now("2026-03-20T09:00:00Z")
                .versions(new TreeMap<>(Map.of(LocalDate.of(2026, 3, 1), everyDay(
                        List.of(MONDAY, FRIDAY), "09:00", "10:00"))))
                .step(60)
                .days(LocalDate.of(2026, 3, 27), LocalDate.of(2026, 3, 30))
                .compute();

        assertThat(starts(slots)).containsExactly(
                Instant.parse("2026-03-27T08:00:00Z"),
                Instant.parse("2026-03-30T07:00:00Z"));
    }

    @Test
    void nightBandOnTheDayOfTheChangeHasNoDuplicates() {
        // Sunday 29 March 2026: at 2:00 clocks jump to 3:00. A band 01:00-04:00 lasts two real hours:
        // 01:00 (00:00Z) and 03:00 (01:00Z). 02:00 does not exist, and no slot appears twice
        List<TimeRange> slots = query()
                .now("2026-03-20T09:00:00Z")
                .versions(new TreeMap<>(Map.of(LocalDate.of(2026, 3, 1),
                        List.of(band(SUNDAY, "01:00", "04:00")))))
                .step(60)
                .days(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29))
                .compute();

        assertThat(starts(slots)).containsExactly(
                Instant.parse("2026-03-29T00:00:00Z"),
                Instant.parse("2026-03-29T01:00:00Z"));
    }

    @Test
    void octoberChangeKeepsNineInTheMorningInRome() {
        // Friday 23 October 2026 is still UTC+2, Monday 26 back to UTC+1 (the change is on Sunday 25)
        List<TimeRange> slots = query()
                .now("2026-10-16T08:00:00Z")
                .versions(new TreeMap<>(Map.of(LocalDate.of(2026, 10, 1), everyDay(
                        List.of(MONDAY, FRIDAY), "09:00", "10:00"))))
                .step(60)
                .days(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 26))
                .compute();

        assertThat(starts(slots)).containsExactly(
                Instant.parse("2026-10-23T07:00:00Z"),
                Instant.parse("2026-10-26T08:00:00Z"));
    }

    // ***** MIDNIGHT *****

    @Test
    void twoHourSessionDoesNotFitIntoA2200To2330Band() {
        // with LocalTime, 22:00 + 2h = 00:00, which is not after 23:30: the slot would be wrongly accepted
        List<TimeRange> slots = query().versions(mondayOnly("22:00", "23:30")).duration(120).compute();

        assertThat(slots).isEmpty();
    }

    @Test
    void lateBandStillWorksForShorterSessions() {
        List<TimeRange> slots = query().versions(mondayOnly("22:00", "23:30")).duration(90).compute();

        assertThat(starts(slots)).containsExactlyElementsOf(at("2026-11-09T22:00:00+01:00"));
    }

    // ***** VERSIONS (D-034) *****

    @Test
    void newVersionAppliesFromItsFirstDay() {
        // until Monday 9 mornings 9-10, from Tuesday 10 afternoons 15-16
        TreeMap<LocalDate, List<WorkingBand>> versions = new TreeMap<>();
        versions.put(LocalDate.of(2026, 11, 1), everyDay(List.of(MONDAY, TUESDAY), "09:00", "10:00"));
        versions.put(LocalDate.of(2026, 11, 10), everyDay(List.of(MONDAY, TUESDAY), "15:00", "16:00"));

        List<TimeRange> slots = query()
                .versions(versions)
                .step(60)
                .days(MONDAY_9_NOVEMBER, LocalDate.of(2026, 11, 10))
                .compute();

        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00:00+01:00", "2026-11-10T15:00:00+01:00"));
    }

    @Test
    void noVersionMeansNoSlots() {
        assertThat(query().versions(new TreeMap<>()).compute()).isEmpty();
    }

    @Test
    void versionStartingAfterTheRequestedDaysGivesNoSlots() {
        TreeMap<LocalDate, List<WorkingBand>> versions = new TreeMap<>();
        versions.put(LocalDate.of(2026, 12, 1), WEEKDAYS);

        assertThat(query().versions(versions).compute()).isEmpty();
    }

    @Test
    void versionWithoutBandsGivesNoSlots() {
        TreeMap<LocalDate, List<WorkingBand>> versions = new TreeMap<>();
        versions.put(LocalDate.of(2026, 11, 1), List.of());

        assertThat(query().versions(versions).compute()).isEmpty();
    }

    @Test
    void bandsOutOfOrderGiveSlotsInOrder() {
        List<TimeRange> slots = query()
                .versions(new TreeMap<>(Map.of(LocalDate.of(2026, 11, 1), List.of(
                        band(MONDAY, "14:00", "15:00"),
                        band(MONDAY, "09:00", "10:00")))))
                .step(60)
                .compute();

        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00:00+01:00", "2026-11-09T14:00:00+01:00"));
    }

    // ***** INVALID QUERIES *****

    @Test
    void fromAfterToIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> query().days(LocalDate.of(2026, 11, 10), MONDAY_9_NOVEMBER).compute());
    }

    @Test
    void slotStepOf45IsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> query().step(45).compute());
    }

    @Test
    void zeroDurationIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> query().duration(0).compute());
    }

    // ***** helpers *****

    // the example of Anna, changed one value at a time by each test
    private static Query query() {
        return new Query();
    }

    private static final class Query {

        private TreeMap<LocalDate, List<WorkingBand>> versions =
                new TreeMap<>(Map.of(LocalDate.of(2026, 11, 1), WEEKDAYS));
        private List<TimeRange> busy = List.of();
        private int durationMinutes = 60;
        private int step = 30;
        private LocalDate from = MONDAY_9_NOVEMBER;
        private LocalDate to = MONDAY_9_NOVEMBER;
        private Instant now = Instant.parse("2026-11-06T09:00:00Z");

        Query versions(TreeMap<LocalDate, List<WorkingBand>> versions) {
            this.versions = versions;
            return this;
        }

        Query busy(TimeRange... busy) {
            this.busy = List.of(busy);
            return this;
        }

        Query duration(int minutes) {
            this.durationMinutes = minutes;
            return this;
        }

        Query step(int minutes) {
            this.step = minutes;
            return this;
        }

        Query days(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
            return this;
        }

        Query now(String instant) {
            this.now = Instant.parse(instant);
            return this;
        }

        List<TimeRange> compute() {
            return FreeSlots.compute(new SlotQuery(versions, busy, Duration.ofMinutes(durationMinutes),
                    step, 24, 21, from, to, now));
        }
    }

    private static TreeMap<LocalDate, List<WorkingBand>> mondayOnly(String start, String end) {
        return new TreeMap<>(Map.of(LocalDate.of(2026, 11, 1), List.of(band(MONDAY, start, end))));
    }

    private static TreeMap<LocalDate, List<WorkingBand>> everyDayFrom(String start, String end) {
        return new TreeMap<>(Map.of(LocalDate.of(2026, 11, 1), everyDay(Arrays.asList(DayOfWeek.values()), start, end)));
    }

    // the same bands on each of the given days; times in pairs: start, end, start, end...
    private static List<WorkingBand> everyDay(List<DayOfWeek> days, String... times) {
        List<WorkingBand> bands = new ArrayList<>();
        for (DayOfWeek day : days) {
            for (int i = 0; i < times.length; i += 2) {
                bands.add(band(day, times[i], times[i + 1]));
            }
        }
        return bands;
    }

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    private static List<Instant> at(String... instants) {
        return Arrays.stream(instants).map(Instant::parse).toList();
    }

    private static List<Instant> starts(List<TimeRange> slots) {
        return slots.stream().map(TimeRange::start).toList();
    }
}
