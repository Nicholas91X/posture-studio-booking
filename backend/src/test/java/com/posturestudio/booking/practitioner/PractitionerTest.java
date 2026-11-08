package com.posturestudio.booking.practitioner;

import static com.posturestudio.booking.practitioner.BookingApproval.AUTOMATIC;
import static com.posturestudio.booking.practitioner.BookingApproval.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: no Spring, no database. The protected constructor is visible from the same package.
public class PractitionerTest {

    // the database defaults after V4
    private static final PractitionerSettings DEFAULTS = new PractitionerSettings(24, 90, 60, 24, 21, 4, MANUAL, 12);

    @Test
    void validSettingsAreApplied() {
        // arrange
        Practitioner practitioner = new Practitioner();

        // act
        practitioner.changeSettings(new PractitionerSettings(48, 60, 30, 48, 14, 6, AUTOMATIC, 24));

        // assert
        assertThat(practitioner.settings()).isEqualTo(new PractitionerSettings(48, 60, 30, 48, 14, 6, AUTOMATIC, 24));
    }

    @Test
    void zeroCancellationNoticeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(0, 90, 60, 24, 21, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("cancellationNoticeHours");
    }

    @Test
    void negativeCreditValidityIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, -1, 60, 24, 21, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("singleCreditValidityDays");
    }

    @Test
    void slotStepOtherThan30Or60IsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 45, 24, 21, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("slotStepMinutes");
    }

    @Test
    void zeroMinBookingNoticeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 0, 21, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("minBookingNoticeHours");
    }

    @Test
    void zeroBookingHorizonIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 0, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("bookingHorizonDays");
    }

    @Test
    void zeroMaxFutureBookingsIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 21, 0, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("maxFutureBookings");
    }

    @Test
    void missingApprovalModeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(NullPointerException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 21, 4, null, 12)));
        assertThat(exception.getMessage()).contains("bookingApproval");
    }

    @Test
    void approvalWindowLongerThanTheNoticeIsRejected() {
        // the practitioner lowers the notice to 6 hours and keeps a 12-hour window: the answer
        // could arrive after the session starts (D-030)
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 6, 21, 4, MANUAL, 12)));
        assertThat(exception.getMessage()).contains("approvalWindowHours");
    }

    @Test
    void approvalWindowEqualToTheNoticeIsAccepted() {
        // slots start MORE than notice hours after the request, so the deadline still comes first
        Practitioner practitioner = new Practitioner();

        practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 21, 4, MANUAL, 24));

        assertThat(practitioner.settings().approvalWindowHours()).isEqualTo(24);
    }

    @Test
    void approvalWindowOver48HoursIsRejectedEvenWithALongNotice() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 200, 21, 4, MANUAL, 49)));
        assertThat(exception.getMessage()).contains("approvalWindowHours");
    }

    @Test
    void zeroApprovalWindowIsRejected() {
        Practitioner practitioner = new Practitioner();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 21, 4, MANUAL, 0)));
    }

    @Test
    void invalidChangeLeavesSettingsUnchanged() {
        // arrange
        Practitioner practitioner = new Practitioner();
        practitioner.changeSettings(DEFAULTS);

        // act: all values are valid but the last one, which exceeds the new notice
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(48, 60, 30, 6, 14, 6, AUTOMATIC, 12)));

        // assert
        assertThat(practitioner.settings()).isEqualTo(DEFAULTS);
    }

    @Test
    void nullSettingsAreRejected() {
        Practitioner practitioner = new Practitioner();

        Assertions.assertThrows(NullPointerException.class, () -> practitioner.changeSettings(null));
    }
}
