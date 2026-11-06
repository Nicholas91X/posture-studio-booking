package com.posturestudio.booking.schedule;

import static com.posturestudio.booking.FixedClockConfiguration.TODAY;
import static com.posturestudio.booking.schedule.WorkingScheduleTest.band;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.NotFoundException;

// ScheduleService.freeSlots: the glue around FreeSlots (guide 17, part 6). The calculation itself is
// tested in FreeSlotsTest; here, that the right data reaches it. Today is Friday 6 November 2026,
// 10:00 in Rome. Same configuration as ScheduleServiceTest: same Spring context, same container.
// No bookings here (NothingOccupied): free slots with bookings are tested in BookingFreeSlotsTest
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, ScheduleService.class,
        CatalogService.class, PractitionerService.class, AccountService.class, NothingOccupied.class})
public class ScheduleFreeSlotsTest {

    private static final LocalDate MONDAY_9_NOVEMBER = LocalDate.of(2026, 11, 9);

    @Autowired
    ScheduleService service;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;
    private long seduta;

    @BeforeEach
    void annaWorksMondayToFriday() {
        anna = insertPractitioner("anna@example.com", true);
        marco = insertPractitioner("marco@example.com", true);
        seduta = insertService(anna, 60, true);
        List<WorkingBand> weekdays = new ArrayList<>();
        for (DayOfWeek day : List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)) {
            weekdays.add(band(day, "09:00", "13:00"));
            weekdays.add(band(day, "14:00", "19:00"));
        }
        service.createVersion(anna, new ScheduleVersionRequest(TODAY, weekdays));
    }

    @Test
    void annasMondayFromTheDatabase() {
        // the example of guide 13, with every piece coming from where it lives in production
        jdbc.update("update practitioner set slot_step_minutes = 30 where id = ?", anna);
        absence(anna, "2026-11-09T11:15:00+01:00", "2026-11-09T12:00:00+01:00");

        List<PublicSlot> slots = service.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER);

        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00+01:00", "2026-11-09T09:30+01:00", "2026-11-09T10:00+01:00",
                "2026-11-09T12:00+01:00",
                "2026-11-09T14:00+01:00", "2026-11-09T14:30+01:00", "2026-11-09T15:00+01:00",
                "2026-11-09T15:30+01:00", "2026-11-09T16:00+01:00", "2026-11-09T16:30+01:00",
                "2026-11-09T17:00+01:00", "2026-11-09T17:30+01:00", "2026-11-09T18:00+01:00"));
        assertThat(slots.getFirst().end()).isEqualTo(OffsetDateTime.parse("2026-11-09T10:00+01:00"));
    }

    @Test
    void settingsReachTheCalculation() {
        // the default step of V3 is 60: hourly starts only
        absence(anna, "2026-11-09T11:15:00+01:00", "2026-11-09T12:00:00+01:00");

        List<PublicSlot> slots = service.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER);

        assertThat(starts(slots)).containsExactlyElementsOf(at(
                "2026-11-09T09:00+01:00", "2026-11-09T10:00+01:00", "2026-11-09T12:00+01:00",
                "2026-11-09T14:00+01:00", "2026-11-09T15:00+01:00", "2026-11-09T16:00+01:00",
                "2026-11-09T17:00+01:00", "2026-11-09T18:00+01:00"));
    }

    @Test
    void durationComesFromTheCatalog() {
        long valutazione = insertService(anna, 30, true);
        jdbc.update("update practitioner set slot_step_minutes = 30 where id = ?", anna);

        List<PublicSlot> slots = service.freeSlots(anna, valutazione, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER);

        // a 30-minute session fits until 12:30 in the morning band
        assertThat(starts(slots)).contains(OffsetDateTime.parse("2026-11-09T12:30+01:00"));
        assertThat(slots.getFirst().end()).isEqualTo(OffsetDateTime.parse("2026-11-09T09:30+01:00"));
    }

    @Test
    void absenceAcrossTheStartOfTheWindowIsTakenIntoAccount() {
        // started before now (Friday 10:00), ends on Monday at 10:00. If the parameters of the
        // window query were swapped, this absence would not be found and 9:00 would be free
        absence(anna, "2026-11-06T08:00:00+01:00", "2026-11-09T10:00:00+01:00");

        List<PublicSlot> slots = service.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER);

        assertThat(starts(slots).getFirst()).isEqualTo(OffsetDateTime.parse("2026-11-09T10:00+01:00"));
    }

    @Test
    void absenceOfAnotherPractitionerChangesNothing() {
        absence(marco, "2026-11-09T00:00:00+01:00", "2026-11-10T00:00:00+01:00");

        assertThat(service.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER)).hasSize(9);
    }

    @Test
    void serviceNotOnSaleIsNotFoundWithTheSameMessageWhateverTheReason() {
        long deactivated = insertService(anna, 60, false);
        long marcos = insertService(marco, 60, true);
        long missing = marcos + 1000;

        var notActive = Assertions.assertThrows(NotFoundException.class,
                () -> service.freeSlots(anna, deactivated, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER));
        var someoneElses = Assertions.assertThrows(NotFoundException.class,
                () -> service.freeSlots(anna, marcos, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER));
        var notThere = Assertions.assertThrows(NotFoundException.class,
                () -> service.freeSlots(anna, missing, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER));

        // nothing in the message tells the three apart, apart from the id itself
        assertThat(notActive.getMessage().replace(String.valueOf(deactivated), "ID"))
                .isEqualTo(someoneElses.getMessage().replace(String.valueOf(marcos), "ID"))
                .isEqualTo(notThere.getMessage().replace(String.valueOf(missing), "ID"));
    }

    @Test
    void deactivatedPractitionerHasNoFreeSlots() {
        jdbc.update("update app_user set active = false where id = (select user_id from practitioner where id = ?)", anna);

        Assertions.assertThrows(NotFoundException.class,
                () -> service.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER));
    }

    // ***** helpers *****

    private void absence(long practitionerId, String startsAt, String endsAt) {
        service.createUnavailability(practitionerId, new NewUnavailability(
                OffsetDateTime.parse(startsAt), OffsetDateTime.parse(endsAt), "Privato"));
    }

    private static List<OffsetDateTime> at(String... times) {
        return Arrays.stream(times).map(OffsetDateTime::parse).toList();
    }

    private static List<OffsetDateTime> starts(List<PublicSlot> slots) {
        return slots.stream().map(PublicSlot::start).toList();
    }

    private long insertService(long practitionerId, int durationMinutes, boolean active) {
        return jdbc.queryForObject(
                "insert into service (practitioner_id, name, duration_minutes, price_cents, active) values (?, 'Seduta', ?, 3000, ?) returning id",
                Long.class, practitionerId, durationMinutes, active);
    }

    private long insertPractitioner(String email, boolean active) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email, active) values ('PRACTITIONER', ?, ?) returning id",
                Long.class, email, active);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
