package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
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
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

// BookingService.request and its checks, in the order of guide 19, part 2. Every module is the real one.
// Today is Friday 6 November 2026, 10:00 in Rome. Anna works Monday to Friday 9-13 and 14-19, hourly
// starts, sessions of 60 minutes at 30 euros. Same configuration as BookingFreeSlotsTest: same context
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class, com.posturestudio.booking.credit.CreditService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
public class BookingServiceTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";
    private static final String MONDAY_10 = "2026-11-09T10:00+01:00";
    private static final String TUESDAY_9 = "2026-11-10T09:00+01:00";
    private static final String TUESDAY_10 = "2026-11-10T10:00+01:00";

    @Autowired
    BookingService service;

    @Autowired
    JdbcTemplate jdbc;

    private BookingTestData data;
    private long anna;
    private long marco;
    private long seduta;
    private long cliente;
    private long altroCliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        seduta = data.service(anna, 60);
        cliente = data.customer("cliente@example.com");
        altroCliente = data.customer("altro@example.com");
        data.weekdays(anna, "2026-11-02");
    }

    // ***** the request that works *****

    @Test
    void aFreeSlotBecomesAPendingPaymentHeldFor32Minutes() {
        CustomerBookingView booked = request(cliente, MONDAY_9);

        assertThat(booked.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booked.endsAt()).isEqualTo(OffsetDateTime.parse(MONDAY_10));
        assertThat(booked.holdExpiresAt()).isEqualTo(OffsetDateTime.parse("2026-11-06T10:32+01:00"));
        // the row, not the object in memory: saveAndFlush has already written it
        Map<String, Object> row = jdbc.queryForMap(
                "select customer_id, status, booked_by, paid_with, price_cents from booking where id = ?", booked.id());
        assertThat(row).containsEntry("customer_id", cliente)
                .containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("booked_by", "CUSTOMER")
                .containsEntry("paid_with", "CARD")
                .containsEntry("price_cents", 3000L);
    }

    @Test
    void thePriceIsTheOneOfTheServiceAtBookingTime() {
        jdbc.update("update service set price_cents = 3500 where id = ?", seduta);

        assertThat(request(cliente, MONDAY_9).priceCents()).isEqualTo(3500);
    }

    @Test
    void aServiceNotOnSaleIsNotFound() {
        jdbc.update("update service set active = false where id = ?", seduta);

        Assertions.assertThrows(NotFoundException.class, () -> request(cliente, MONDAY_9));
    }

    // ***** the slot: one code for every rule of the calculation (guide 19, 2.2) *****

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-09T09:30+01:00",   // off the hourly grid
            "2026-11-09T08:00+01:00",   // before the morning band
            "2026-11-09T13:00+01:00",   // in the lunch break
            "2026-11-14T09:00+01:00",   // Saturday
            "2026-11-30T09:00+01:00"})  // beyond the horizon of 21 days (last day: 27 November)
    void aStartThatIsNotAFreeSlotIsRejected(String startsAt) {
        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> request(cliente, startsAt));
    }

    @Test
    void aSlotWithinTheMinimumNoticeIsRejected() {
        // Monday 9:00 is 71 hours after now
        jdbc.update("update practitioner set min_booking_notice_hours = 72 where id = ?", anna);

        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> request(cliente, MONDAY_9));
    }

    @Test
    void aSlotDuringAnAbsenceIsRejected() {
        data.absence(anna, MONDAY_9, MONDAY_10);

        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> request(cliente, MONDAY_9));
    }

    @Test
    void aSlotTakenByAnotherCustomerIsRejected() {
        data.booking(anna, seduta, altroCliente, MONDAY_9, MONDAY_10, "CONFIRMED");

        assertRejected(ErrorCode.SLOT_NOT_AVAILABLE, () -> request(cliente, MONDAY_9));
    }

    @Test
    void anExpiredHoldOnTheSlotIsClosedAndTheSlotIsBooked() {
        // the other customer's hold ended at 9:45 and the job has not passed yet (guide 19, part 5)
        long stale = data.booking(anna, seduta, altroCliente, MONDAY_9, MONDAY_10, "PENDING_PAYMENT", "2026-11-06T09:45+01:00");

        CustomerBookingView booked = request(cliente, MONDAY_9);

        assertThat(booked.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        Map<String, Object> old = jdbc.queryForMap("select status, version from booking where id = ?", stale);
        // EXPIRED, and the version moved: a concurrent transition of the same row will notice (piece 3)
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("version", 1L);
    }

    @Test
    void onlyExpiredHoldsOnTheRequestedSlotAreClosed() {
        long elsewhere = data.booking(anna, seduta, altroCliente, TUESDAY_9, TUESDAY_10, "PENDING_PAYMENT", "2026-11-06T09:45+01:00");

        request(cliente, MONDAY_9);

        // Tuesday is left to the job: the lazy expiry touches only what the new booking needs
        String status = jdbc.queryForObject("select status from booking where id = ?", String.class, elsewhere);
        assertThat(status).isEqualTo("PENDING_PAYMENT");
    }

    // ***** the limit (D-023, D-041) *****

    @Test
    void theLimitStopsTheRequest() {
        jdbc.update("update practitioner set max_future_bookings = 2 where id = ?", anna);
        data.booking(anna, seduta, cliente, TUESDAY_9, TUESDAY_10, "PENDING_APPROVAL");
        data.booking(anna, seduta, cliente, "2026-11-11T09:00+01:00", "2026-11-11T10:00+01:00", "CONFIRMED");

        assertRejected(ErrorCode.BOOKING_LIMIT_REACHED, () -> request(cliente, MONDAY_9));
    }

    @Test
    void bookingsThatDoNotHoldAFutureSlotDoNotCount() {
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        // an expired hold, a rejected booking, a past session, a booking with Marco: none counts
        data.booking(anna, seduta, cliente, TUESDAY_9, TUESDAY_10, "PENDING_PAYMENT", "2026-11-06T09:45+01:00");
        data.booking(anna, seduta, cliente, "2026-11-11T09:00+01:00", "2026-11-11T10:00+01:00", "REJECTED");
        data.booking(anna, seduta, cliente, "2026-11-05T09:00+01:00", "2026-11-05T10:00+01:00", "CONFIRMED");
        long marcosService = data.service(marco, 60);
        data.booking(marco, marcosService, cliente, "2026-11-12T09:00+01:00", "2026-11-12T10:00+01:00", "CONFIRMED");

        assertThat(request(cliente, MONDAY_9).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void aSessionStopsCountingWhenItStarts() {
        // D-041: started at 9:00 today, ends at 11:00. Not in the future any more: the place is free again
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        data.booking(anna, seduta, cliente, "2026-11-06T09:00+01:00", "2026-11-06T11:00+01:00", "CONFIRMED");

        assertThat(request(cliente, MONDAY_9).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void aPanelBookingCountsInTheLimit() {
        // the practitioner books without limits, but what they book counts when the customer books alone (D-023)
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        data.panelBooking(anna, seduta, cliente, TUESDAY_9, TUESDAY_10);

        assertRejected(ErrorCode.BOOKING_LIMIT_REACHED, () -> request(cliente, MONDAY_9));
    }

    @Test
    void theLimitComesBeforeTheSlot() {
        // both fail: the customer is told about the limit, which no other slot would solve
        jdbc.update("update practitioner set max_future_bookings = 1 where id = ?", anna);
        data.booking(anna, seduta, cliente, TUESDAY_9, TUESDAY_10, "CONFIRMED");
        data.booking(anna, seduta, altroCliente, MONDAY_9, MONDAY_10, "CONFIRMED");

        assertRejected(ErrorCode.BOOKING_LIMIT_REACHED, () -> request(cliente, MONDAY_9));
    }

    // ***** reading *****

    @Test
    void aCustomerReadsOnlyTheirOwnBooking() {
        long id = request(cliente, MONDAY_9).id();

        assertThat(service.findForCustomer(cliente, id).startsAt()).isEqualTo(OffsetDateTime.parse(MONDAY_9));
        Assertions.assertThrows(NotFoundException.class, () -> service.findForCustomer(altroCliente, id));
    }

    // ***** helpers *****

    private CustomerBookingView request(long customerId, String startsAt) {
        return service.request(customerId, new NewBooking(anna, seduta, OffsetDateTime.parse(startsAt)));
    }

    private static void assertRejected(ErrorCode code, Runnable request) {
        var exception = Assertions.assertThrows(BusinessRuleException.class, request::run);
        assertThat(exception.getCode()).isEqualTo(code);
    }
}
