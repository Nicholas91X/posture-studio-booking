package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;

// The lists and the two flags (guide 23). Today is Friday 6 November 2026, 10:00 in Rome; the week
// looked at is Monday 9 - Sunday 15 November. Anna and Marco share the room. Same context as the
// other booking tests
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class, com.posturestudio.booking.credit.CreditService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
public class BookingListsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 15);

    @Autowired
    BookingService service;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long marco;
    private long annasSession;
    private long annasEvaluation;
    private long marcosSession;
    private long marcosEvaluation;
    private long mario;
    private long segreto;

    @BeforeEach
    void annaAndMarcoShareTheRoom() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        annasSession = data.service(anna, 60);
        annasEvaluation = data.evaluation(anna);
        marcosSession = data.service(marco, 60);
        marcosEvaluation = data.evaluation(marco);
        mario = data.customer("mario@example.com", "Mario", "Rossi");
        // Marco's customer: Anna must never see this name
        segreto = data.customer("segreto@example.com", "Segreto", "Cliente");
        data.weekdays(anna, "2026-11-02");
    }

    // ***** the practitioner's list *****

    @Test
    void theListHasOnlyTheirOwnBookingsInOrderWithTheirCustomersNames() {
        long wednesday = booking(anna, annasSession, mario, "2026-11-11T09:00", "2026-11-11T10:00", "PENDING_APPROVAL");
        long monday = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");
        long tuesday = booking(anna, annasSession, mario, "2026-11-10T09:00", "2026-11-10T10:00", "REJECTED");
        booking(marco, marcosSession, segreto, "2026-11-09T11:00", "2026-11-09T12:00", "CONFIRMED");

        List<PanelBookingRow> rows = service.listForPanel(anna, MONDAY, SUNDAY);

        assertThat(rows).extracting(PanelBookingRow::id).containsExactly(monday, tuesday, wednesday);
        assertThat(rows.getFirst().customerFirstName()).isEqualTo("Mario");
        assertThat(rows.getFirst().customerLastName()).isEqualTo("Rossi");
    }

    @Test
    void aBookingThatStartsBeforeThePeriodAndEndsInsideIsListed() {
        long acrossMidnight = booking(anna, annasSession, mario, "2026-11-09T23:30", "2026-11-10T00:30", "CONFIRMED");

        assertThat(service.listForPanel(anna, LocalDate.of(2026, 11, 10), SUNDAY))
                .extracting(PanelBookingRow::id).containsExactly(acrossMidnight);
    }

    @Test
    void aBookingLeftOutsideByANewScheduleVersionIsFlagged() {
        // from Monday 9 November no more Friday afternoons (D-034)
        Long version = jdbc.queryForObject(
                "insert into working_schedule (practitioner_id, valid_from) values (?, '2026-11-09') returning id",
                Long.class, anna);
        for (int day = 1; day <= 5; day++) {
            jdbc.update("insert into working_hours (schedule_id, day_of_week, start_time, end_time) values (?, ?, '09:00', '13:00')",
                    version, day);
        }
        long fridayAfternoon = booking(anna, annasSession, mario, "2026-11-13T15:00", "2026-11-13T16:00", "CONFIRMED");
        long mondayMorning = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");

        List<PanelBookingRow> rows = service.listForPanel(anna, MONDAY, SUNDAY);

        assertThat(row(rows, fridayAfternoon).outsideWorkingHours()).isTrue();
        assertThat(row(rows, mondayMorning).outsideWorkingHours()).isFalse();
    }

    // ***** one room (D-043) *****

    @Test
    void aSessionDuringTheOtherPractitionersEvaluationIsFlaggedWithTheSharedInterval() {
        long session = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");
        booking(marco, marcosEvaluation, segreto, "2026-11-09T09:30", "2026-11-09T10:00", "PENDING_APPROVAL");

        assertThat(row(service.listForPanel(anna, MONDAY, SUNDAY), session).evaluationOverlaps())
                .containsExactly(overlap("2026-11-09T09:30", "2026-11-09T10:00"));
    }

    @Test
    void anEvaluationDuringTheOtherPractitionersSessionIsFlaggedToo() {
        // the flag goes to both practitioners (requirements, open point 12)
        long evaluation = booking(anna, annasEvaluation, mario, "2026-11-09T09:00", "2026-11-09T09:30", "CONFIRMED");
        booking(marco, marcosSession, segreto, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");

        assertThat(row(service.listForPanel(anna, MONDAY, SUNDAY), evaluation).evaluationOverlaps())
                .containsExactly(overlap("2026-11-09T09:00", "2026-11-09T09:30"));
    }

    @Test
    void twoSessionsAtTheSameTimeAreNotFlagged() {
        // the normal case: two practitioners in the room with their customers
        long session = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");
        booking(marco, marcosSession, segreto, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");

        assertThat(row(service.listForPanel(anna, MONDAY, SUNDAY), session).evaluationOverlaps()).isEmpty();
    }

    @Test
    void anEvaluationThatNoLongerHoldsTheTimeIsNotFlagged() {
        long session = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");
        booking(marco, marcosEvaluation, segreto, "2026-11-09T09:30", "2026-11-09T10:00", "REJECTED");

        assertThat(row(service.listForPanel(anna, MONDAY, SUNDAY), session).evaluationOverlaps()).isEmpty();
    }

    @Test
    void aBookingThatNoLongerHoldsTheTimeGetsNoFlags() {
        long rejected = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "REJECTED");
        booking(marco, marcosEvaluation, segreto, "2026-11-09T09:30", "2026-11-09T10:00", "CONFIRMED");

        PanelBookingRow row = row(service.listForPanel(anna, MONDAY, SUNDAY), rejected);
        assertThat(row.evaluationOverlaps()).isEmpty();
        assertThat(row.outsideWorkingHours()).isFalse();
    }

    // ***** requests and the customer's list *****

    @Test
    void theRequestsAreTheOnesStillAnswerable() {
        long answerable = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "PENDING_APPROVAL");
        long pastDeadline = booking(anna, annasSession, mario, "2026-11-10T09:00", "2026-11-10T10:00", "PENDING_APPROVAL");
        jdbc.update("update booking set approval_deadline = '2026-11-06T09:30+01:00' where id = ?", pastDeadline);
        booking(marco, marcosSession, segreto, "2026-11-09T09:00", "2026-11-09T10:00", "PENDING_APPROVAL");

        assertThat(service.requestsForPanel(anna)).extracting(PanelBookingRow::id).containsExactly(answerable);
    }

    @Test
    void aCustomerSeesOnlyTheirOwnBookingsTheFurthestFirst() {
        long monday = booking(anna, annasSession, mario, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");
        long tuesday = booking(anna, annasSession, mario, "2026-11-10T09:00", "2026-11-10T10:00", "PENDING_APPROVAL");
        booking(marco, marcosSession, segreto, "2026-11-09T09:00", "2026-11-09T10:00", "CONFIRMED");

        assertThat(service.listForCustomer(mario)).extracting(CustomerBookingView::id).containsExactly(tuesday, monday);
    }

    @Test
    void anEmptyPeriodIsAnEmptyList() {
        assertThat(service.listForPanel(anna, MONDAY, SUNDAY)).isEmpty();
    }

    // ***** helpers *****

    // times in Rome in November: +01:00
    private long booking(long practitioner, long serviceId, long customer, String startsAt, String endsAt, String status) {
        return data.booking(practitioner, serviceId, customer, startsAt + "+01:00", endsAt + "+01:00", status);
    }

    private static RoomOverlap overlap(String start, String end) {
        return new RoomOverlap(OffsetDateTime.parse(start + "+01:00"), OffsetDateTime.parse(end + "+01:00"));
    }

    private static PanelBookingRow row(List<PanelBookingRow> rows, long id) {
        return rows.stream().filter(row -> row.id() == id).findFirst().orElseThrow();
    }
}
