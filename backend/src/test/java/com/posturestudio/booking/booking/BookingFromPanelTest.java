package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerDetails;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

// Booking from the panel (guide 22): which calendar rules apply, and who the customer is.
// Today is Friday 6 November 2026, 10:00 in Rome. Anna works Monday to Friday 9-13 and 14-19,
// hourly starts, sessions of 60 minutes. Same context as the other booking tests
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
@RecordApplicationEvents
public class BookingFromPanelTest {

    private static final CustomerDetails MARIO = new CustomerDetails("mario.rossi@example.com", "Mario", "Rossi");

    @Autowired
    BookingService service;

    @Autowired
    ApplicationEvents events;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long seduta;

    @BeforeEach
    void annaWorksMondayToFriday() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        data.weekdays(anna, "2026-11-02");
    }

    // ***** the booking *****

    @Test
    void aBookingForANewCustomerIsConfirmedAndPaidInTheStudio() {
        PanelBookingView booked = book("2026-11-09T09:00+01:00", MARIO);

        assertThat(booked.status()).isEqualTo(BookingStatus.CONFIRMED);
        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "select booked_by, paid_with, hold_expires_at, decided_at from booking where id = ?", booked.id());
        assertThat(row).containsEntry("booked_by", "PRACTITIONER").containsEntry("paid_with", "IN_STUDIO");
        assertThat(row.get("hold_expires_at")).isNull();
        assertThat(row.get("decided_at")).isNotNull();
        String email = jdbc.queryForObject(
                "select u.email from customer c join app_user u on u.id = c.user_id where c.id = ?",
                String.class, booked.customerId());
        assertThat(email).isEqualTo("mario.rossi@example.com");
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(BookingConfirmed.class);
    }

    @Test
    void theSameEmailIsTheSameCustomer() {
        long first = book("2026-11-09T09:00+01:00", MARIO).customerId();

        long second = book("2026-11-10T09:00+01:00",
                new CustomerDetails("MARIO.ROSSI@example.com", "Mario", "Rossi")).customerId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theEmailOfAPractitionerIsRefused() {
        assertRejected(ErrorCode.EMAIL_NOT_A_CUSTOMER,
                () -> book("2026-11-09T09:00+01:00", new CustomerDetails("anna@example.com", "Anna", "X")));
    }

    // ***** which calendar rules apply (guide 22, part 2) *****

    @Test
    void offTheGridIsAllowed() {
        // hourly starts for customers; after a 9:00-9:30 evaluation the practitioner puts a session at 9:30
        assertThat(book("2026-11-09T09:30+01:00", MARIO).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void withinTheNoticeAndBeyondTheHorizonIsAllowed() {
        assertThat(book("2026-11-06T15:00+01:00", MARIO).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(book("2026-12-14T09:00+01:00", MARIO).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-09T13:00+01:00",   // in the lunch break
            "2026-11-09T12:30+01:00",   // 12:30-13:30 runs over the end of the morning band
            "2026-11-09T08:00+01:00",   // before work
            "2026-11-14T09:00+01:00"})  // Saturday
    void outsideTheWorkingBandsIsRefused(String startsAt) {
        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> book(startsAt, MARIO));
    }

    @Test
    void duringAnAbsenceIsRefused() {
        data.absence(anna, "2026-11-09T09:30+01:00", "2026-11-09T11:00+01:00");

        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> book("2026-11-09T09:00+01:00", MARIO));
    }

    @Test
    void inThePastIsRefused() {
        // 9:00 today, now is 10:00
        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> book("2026-11-06T09:00+01:00", MARIO));
    }

    @Test
    void overAnotherBookingTheConstraintAnswersAndIsTranslated() {
        // no Java check on bookings here: the exclusion constraint itself refuses, and BookingService
        // translates it. The deterministic proof of guide 19, part 6
        long other = data.customer("altro@example.com");
        data.booking(anna, seduta, other, "2026-11-09T09:30+01:00", "2026-11-09T10:30+01:00", "CONFIRMED");

        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> book("2026-11-09T09:00+01:00", MARIO));
    }

    @Test
    void theLimitDoesNotApplyButThePanelBookingCountsInIt() {
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        long customer = book("2026-11-09T09:00+01:00", MARIO).customerId();

        // a second one from the panel: no limit for the practitioner
        assertThat(book("2026-11-10T09:00+01:00", MARIO).status()).isEqualTo(BookingStatus.CONFIRMED);
        // the customer alone: limit reached (D-023)
        assertRejected(ErrorCode.BOOKING_LIMIT_REACHED, () -> service.request(customer,
                new NewBooking(anna, seduta, OffsetDateTime.parse("2026-11-11T09:00+01:00"))));
    }

    @Test
    void aServiceOfAnotherPractitionerIsNotFound() {
        long marco = data.practitioner("marco@example.com");

        Assertions.assertThrows(NotFoundException.class, () -> service.bookFromPanel(marco,
                new NewPanelBooking(seduta, OffsetDateTime.parse("2026-11-09T09:00+01:00"), MARIO)));
    }

    // ***** helpers *****

    private PanelBookingView book(String startsAt, CustomerDetails customer) {
        return service.bookFromPanel(anna, new NewPanelBooking(seduta, OffsetDateTime.parse(startsAt), customer));
    }

    private static void assertRejected(ErrorCode code, Runnable call) {
        var exception = Assertions.assertThrows(BusinessRuleException.class, call::run);
        assertThat(exception.getCode()).isEqualTo(code);
    }
}
