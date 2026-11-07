package com.posturestudio.booking.schedule;

import static com.posturestudio.booking.FixedClockConfiguration.TODAY;
import static com.posturestudio.booking.schedule.WorkingScheduleTest.band;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

// Today is Friday 6 November 2026, 10:00 in Rome (FixedClockConfiguration).
// The service joins the test transaction: flush() does what the commit would do, so that
// the database constraints really see the rows
@DataJpaTest
// ScheduleService also asks catalog and practitioner (free slots), which ask auth;
// booked time comes from a fake with no bookings (NothingOccupied)
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, ScheduleService.class,
        CatalogService.class, PractitionerService.class, AccountService.class, NothingOccupied.class})
public class ScheduleServiceTest {

    private static final LocalDate MONDAY_9_NOVEMBER = LocalDate.of(2026, 11, 9);

    private static final List<WorkingBand> WEEKDAYS = List.of(
            band(MONDAY, "09:00", "13:00"),
            band(MONDAY, "14:00", "19:00"),
            band(TUESDAY, "09:00", "13:00"));

    @Autowired
    ScheduleService service;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;

    @BeforeEach
    void insertPractitioners() {
        anna = insertPractitioner("anna@example.com");
        marco = insertPractitioner("marco@example.com");
    }

    // ***** CREATE *****

    @Test
    void futureVersionIsSavedWithItsBands() {
        ScheduleVersionView created = service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));
        entityManager.flush();

        assertThat(created.validFrom()).isEqualTo(MONDAY_9_NOVEMBER);
        assertThat(created.editable()).isTrue();
        assertThat(created.current()).isFalse();
        assertThat(created.bands()).containsExactlyElementsOf(WEEKDAYS);
        assertThat(countBands(created.id())).isEqualTo(3);
    }

    @Test
    void versionFromTodayIsAllowedAndIsTheCurrentOne() {
        ScheduleVersionView created = service.createVersion(anna, request(TODAY, WEEKDAYS));

        assertThat(created.current()).isTrue();
        assertThat(created.editable()).isTrue();
    }

    @Test
    void versionFromYesterdayIsRejected() {
        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> service.createVersion(anna, request(TODAY.minusDays(1), WEEKDAYS)));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.SCHEDULE_STARTS_IN_THE_PAST);
    }

    @Test
    void secondVersionOnTheSameDateIsRejectedByJavaWithItsCode() {
        service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));

        // a BusinessRuleException, not the DataIntegrityViolationException of the unique constraint
        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> service.createVersion(anna, request(MONDAY_9_NOVEMBER, List.of())));
        assertThat(exception.getCode()).isEqualTo(ErrorCode.SCHEDULE_DATE_TAKEN);
    }

    @Test
    void twoPractitionersCanStartOnTheSameDate() {
        service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));

        Assertions.assertDoesNotThrow(() -> service.createVersion(marco, request(MONDAY_9_NOVEMBER, WEEKDAYS)));
    }

    // ***** REPLACE *****

    @Test
    void futureVersionReplacedWithTheSameBandsPasses() {
        // the worst case for the order of the statements at flush (guide 15, part 4):
        // without the flush in the service, the new Monday 9-13 could be inserted before the old
        // one is deleted, and working_hours_no_overlap_ex would reject it
        long id = service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS)).id();
        entityManager.flush();

        ScheduleVersionView replaced = service.replaceVersion(anna, id, request(MONDAY_9_NOVEMBER, WEEKDAYS));
        entityManager.flush();

        assertThat(replaced.bands()).containsExactlyElementsOf(WEEKDAYS);
        assertThat(countBands(id)).isEqualTo(3);
    }

    @Test
    void replacingChangesDateAndBands() {
        long id = service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS)).id();
        entityManager.flush();
        LocalDate june = LocalDate.of(2027, 6, 1);

        ScheduleVersionView replaced = service.replaceVersion(anna, id,
                request(june, List.of(band(MONDAY, "08:00", "14:00"))));
        entityManager.flush();
        entityManager.clear();

        assertThat(replaced.validFrom()).isEqualTo(june);
        assertThat(service.listVersions(anna)).singleElement()
                .satisfies(version -> assertThat(version.bands()).containsExactly(band(MONDAY, "08:00", "14:00")));
    }

    @Test
    void versionStartingTodayCanBeReplaced() {
        // guide 15, part 2: created this morning with a mistake, corrected at 10:00
        long id = service.createVersion(anna, request(TODAY, List.of(band(MONDAY, "09:00", "12:00")))).id();
        entityManager.flush();

        ScheduleVersionView replaced = service.replaceVersion(anna, id, request(TODAY, WEEKDAYS));

        assertThat(replaced.bands()).containsExactlyElementsOf(WEEKDAYS);
    }

    @Test
    void startedVersionCannotBeReplacedOrDeleted() {
        long id = insertVersion(anna, LocalDate.of(2026, 11, 1));

        var replace = Assertions.assertThrows(BusinessRuleException.class,
                () -> service.replaceVersion(anna, id, request(MONDAY_9_NOVEMBER, WEEKDAYS)));
        var delete = Assertions.assertThrows(BusinessRuleException.class,
                () -> service.deleteVersion(anna, id));

        assertThat(replace.getCode()).isEqualTo(ErrorCode.SCHEDULE_ALREADY_STARTED);
        assertThat(delete.getCode()).isEqualTo(ErrorCode.SCHEDULE_ALREADY_STARTED);
    }

    @Test
    void versionCannotBeMovedToTheDateOfAnother() {
        service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));
        long june = service.createVersion(anna, request(LocalDate.of(2027, 6, 1), WEEKDAYS)).id();

        var exception = Assertions.assertThrows(BusinessRuleException.class,
                () -> service.replaceVersion(anna, june, request(MONDAY_9_NOVEMBER, WEEKDAYS)));
        assertThat(exception.getCode()).isEqualTo(ErrorCode.SCHEDULE_DATE_TAKEN);
    }

    @Test
    void versionCanBeReplacedKeepingItsOwnDate() {
        // the date check excludes the version itself
        long id = service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS)).id();

        Assertions.assertDoesNotThrow(() -> service.replaceVersion(anna, id, request(MONDAY_9_NOVEMBER, List.of())));
    }

    // ***** DELETE *****

    @Test
    void deletedFutureVersionDisappearsWithItsBands() {
        long id = service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS)).id();
        entityManager.flush();

        service.deleteVersion(anna, id);
        entityManager.flush();

        assertThat(service.listVersions(anna)).isEmpty();
        assertThat(countBands(id)).isZero();
    }

    // ***** LIST *****

    @Test
    void listShowsVersionsInOrderWithCurrentAndEditable() {
        // arrange: started on 1 November, from today, from next June
        long november = insertVersion(anna, LocalDate.of(2026, 11, 1));
        long today = service.createVersion(anna, request(TODAY, WEEKDAYS)).id();
        long june = service.createVersion(anna, request(LocalDate.of(2027, 6, 1), WEEKDAYS)).id();
        entityManager.flush();
        entityManager.clear();

        // act
        List<ScheduleVersionView> versions = service.listVersions(anna);

        // assert
        assertThat(versions).extracting(ScheduleVersionView::id).containsExactly(november, today, june);
        assertThat(versions).extracting(ScheduleVersionView::current).containsExactly(false, true, false);
        assertThat(versions).extracting(ScheduleVersionView::editable).containsExactly(false, true, true);
    }

    @Test
    void startedVersionIsCurrentWhenNothingNewerHasStarted() {
        long november = insertVersion(anna, LocalDate.of(2026, 11, 1));
        service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));

        assertThat(service.listVersions(anna))
                .filteredOn(ScheduleVersionView::current)
                .extracting(ScheduleVersionView::id)
                .containsExactly(november);
    }

    @Test
    void listLoadsVersionsAndBandsInOneQuery() {
        // arrange: three versions with bands, then an empty persistence context
        service.createVersion(anna, request(MONDAY_9_NOVEMBER, WEEKDAYS));
        service.createVersion(anna, request(LocalDate.of(2027, 1, 4), WEEKDAYS));
        service.createVersion(anna, request(LocalDate.of(2027, 6, 1), WEEKDAYS));
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        // act
        List<ScheduleVersionView> versions = service.listVersions(anna);

        // assert: with lazy bands and no join fetch it would be 1 + 3 (N+1)
        assertThat(versions).allSatisfy(version -> assertThat(version.bands()).hasSize(3));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void listShowsOnlyOwnVersions() {
        service.createVersion(marco, request(MONDAY_9_NOVEMBER, WEEKDAYS));

        assertThat(service.listVersions(anna)).isEmpty();
    }

    // ***** OWNERSHIP *****

    @Test
    void someoneElsesVersionIsNotFound() {
        long marcos = service.createVersion(marco, request(MONDAY_9_NOVEMBER, WEEKDAYS)).id();

        Assertions.assertThrows(NotFoundException.class,
                () -> service.replaceVersion(anna, marcos, request(MONDAY_9_NOVEMBER, WEEKDAYS)));
        Assertions.assertThrows(NotFoundException.class, () -> service.deleteVersion(anna, marcos));
    }

    // ***** UNAVAILABILITIES *****

    @Test
    void absenceIsSavedAsTheSameInstantAndReadInRomeTime() {
        // 11:15 in Rome sent as 10:15 UTC: same instant, shown back with the offset of Rome
        UnavailabilityView created = service.createUnavailability(anna, new NewUnavailability(
                OffsetDateTime.parse("2026-11-12T10:15:00Z"),
                OffsetDateTime.parse("2026-11-12T11:00:00Z"), "  Dentista  "));

        assertThat(created.startsAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T11:15:00+01:00"));
        assertThat(created.reason()).isEqualTo("Dentista");
    }

    @Test
    void blankReasonBecomesNoReason() {
        UnavailabilityView created = service.createUnavailability(anna, absence("2026-11-12T11:15:00+01:00",
                "2026-11-12T12:00:00+01:00", "   "));

        assertThat(created.reason()).isNull();
    }

    @Test
    void listShowsOnlyAbsencesNotOverYetInOrderOfStart() {
        // now is 6 November, 10:00 in Rome
        service.createUnavailability(anna, absence("2026-11-20T09:00:00+01:00", "2026-11-20T13:00:00+01:00", null));
        service.createUnavailability(anna, absence("2026-11-05T09:00:00+01:00", "2026-11-05T13:00:00+01:00", null));
        // started before now, still going on: listed
        service.createUnavailability(anna, absence("2026-11-06T09:00:00+01:00", "2026-11-06T11:00:00+01:00", null));
        service.createUnavailability(anna, absence("2026-11-12T11:15:00+01:00", "2026-11-12T12:00:00+01:00", null));

        assertThat(service.listUpcomingUnavailabilities(anna))
                .extracting(UnavailabilityView::startsAt)
                .containsExactly(
                        OffsetDateTime.parse("2026-11-06T09:00:00+01:00"),
                        OffsetDateTime.parse("2026-11-12T11:15:00+01:00"),
                        OffsetDateTime.parse("2026-11-20T09:00:00+01:00"));
    }

    @Test
    void absenceIsDeletedOnlyByItsOwner() {
        long id = service.createUnavailability(anna,
                absence("2026-11-12T11:15:00+01:00", "2026-11-12T12:00:00+01:00", null)).id();

        Assertions.assertThrows(NotFoundException.class, () -> service.deleteUnavailability(marco, id));
        service.deleteUnavailability(anna, id);

        assertThat(service.listUpcomingUnavailabilities(anna)).isEmpty();
    }

    // ***** helpers *****

    private static ScheduleVersionRequest request(LocalDate validFrom, List<WorkingBand> bands) {
        return new ScheduleVersionRequest(validFrom, bands);
    }

    private static NewUnavailability absence(String startsAt, String endsAt, String reason) {
        return new NewUnavailability(OffsetDateTime.parse(startsAt), OffsetDateTime.parse(endsAt), reason);
    }

    private int countBands(long scheduleId) {
        return jdbc.queryForObject("select count(*) from working_hours where schedule_id = ?", Integer.class, scheduleId);
    }

    // a version that started in the past cannot be created through the service: inserted with SQL
    private long insertVersion(long practitionerId, LocalDate validFrom) {
        return jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, ?) returning id",
                Long.class, practitionerId, validFrom);
    }

    private long insertPractitioner(String email) {
        Long userId = jdbc.queryForObject(
                "insert into app_user (role, email) values ('PRACTITIONER', ?) returning id",
                Long.class, email);
        return jdbc.queryForObject(
                "insert into practitioner (user_id, first_name, last_name) values (?, 'Test', 'Practitioner') returning id",
                Long.class, userId);
    }
}
