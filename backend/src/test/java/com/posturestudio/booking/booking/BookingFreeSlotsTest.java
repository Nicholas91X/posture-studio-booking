package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.posturestudio.booking.schedule.PublicSlot;
import com.posturestudio.booking.schedule.ScheduleService;

// Free slots with real bookings: schedule's ScheduleService with booking's BookingOccupiedTime (D-044).
// This package can import both: ScheduleService is public, BookingOccupiedTime lives here.
// Today is Friday 6 November 2026, 10:00 in Rome. Anna works Monday to Friday 9-13 and 14-19,
// hourly starts (default step 60), sessions of 60 minutes: on Monday 9 November, 9 free slots.
// Same configuration as BookingServiceTest (BookingService and CustomerService unused here): same context
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
public class BookingFreeSlotsTest {

    private static final LocalDate MONDAY_9_NOVEMBER = LocalDate.of(2026, 11, 9);
    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";

    @Autowired
    ScheduleService schedule;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long seduta;
    private long customer;

    @BeforeEach
    void annaWorksMondayToFriday() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        seduta = data.service(anna, 60);
        customer = data.customer("cliente@example.com");
        data.weekdays(anna, "2026-11-02");
    }

    @Test
    void withoutBookingsTheWholeDayIsFree() {
        assertThat(mondayStarts()).hasSize(9).first().isEqualTo(OffsetDateTime.parse(MONDAY_9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_APPROVAL", "CONFIRMED"})
    void aBookingThatHoldsTheTimeHidesItsSlot(String status) {
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, status);

        assertThat(mondayStarts()).hasSize(8).doesNotContain(OffsetDateTime.parse(MONDAY_9));
    }

    @Test
    void aPendingPaymentWithItsHoldStillRunningHidesItsSlot() {
        // the customer is paying: the hold ends at 10:30 today, now is 10:00
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", "2026-11-06T10:30+01:00");

        assertThat(mondayStarts()).doesNotContain(OffsetDateTime.parse(MONDAY_9));
    }

    @Test
    void aPendingPaymentWhoseHoldExpiredIsFreeAgain() {
        // the hold ended at 9:45 and the job has not closed the booking yet: for whoever looks now
        // the slot is free (guide 18, 3.5), even if the database constraint still sees the row
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", "2026-11-06T09:45+01:00");

        assertThat(mondayStarts()).hasSize(9).contains(OffsetDateTime.parse(MONDAY_9));
    }

    @Test
    void aHoldEndingExactlyNowIsExpired() {
        // hold_expires_at > now: a hold ending at 10:00 no longer holds anything at 10:00
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", "2026-11-06T10:00+01:00");

        assertThat(mondayStarts()).contains(OffsetDateTime.parse(MONDAY_9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "REJECTED", "APPROVAL_EXPIRED", "WITHDRAWN"})
    void aFinalBookingLeavesItsSlotFree(String status) {
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, status);

        assertThat(mondayStarts()).hasSize(9);
    }

    @Test
    void aBookingAcrossTwoSlotsHidesBoth() {
        // a 9:30-10:30 booking (made with half-hour starts, before the practitioner switched them off)
        // overlaps both 9-10 and 10-11
        data.booking(anna, seduta, customer, "2026-11-09T09:30+01:00", "2026-11-09T10:30+01:00", "CONFIRMED");

        assertThat(mondayStarts()).hasSize(7)
                .doesNotContain(OffsetDateTime.parse(MONDAY_9), OffsetDateTime.parse(MONDAY_10));
    }

    @Test
    void aBookingOfAnotherPractitionerChangesNothing() {
        long marco = data.practitioner("marco@example.com");
        long marcosService = data.service(marco, 60);
        data.booking(marco, marcosService, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        assertThat(mondayStarts()).hasSize(9);
    }

    @Test
    void theCustomerOfABookingNeverReachesTheAnswer() {
        // the public record has only start and end: who booked cannot leak (guide 17, part 5)
        data.booking(anna, seduta, customer, MONDAY_9, MONDAY_10, "CONFIRMED");

        List<PublicSlot> slots = schedule.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER);

        assertThat(PublicSlot.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("start", "end");
        assertThat(slots).hasSize(8);
    }

    private List<OffsetDateTime> mondayStarts() {
        return schedule.freeSlots(anna, seduta, MONDAY_9_NOVEMBER, MONDAY_9_NOVEMBER).stream()
                .map(PublicSlot::start)
                .toList();
    }
}
