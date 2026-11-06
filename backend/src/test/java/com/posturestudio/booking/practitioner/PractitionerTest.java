package com.posturestudio.booking.practitioner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: no Spring, no database. The protected constructor is visible from the same package.
public class PractitionerTest {

    // the database defaults after V3
    private static final PractitionerSettings DEFAULTS = new PractitionerSettings(24, 90, 60, 24, 21);

    @Test
    void validSettingsAreApplied() {
        // arrange
        Practitioner practitioner = new Practitioner();

        // act
        practitioner.changeSettings(new PractitionerSettings(48, 60, 30, 48, 14));

        // assert
        assertThat(practitioner.settings()).isEqualTo(new PractitionerSettings(48, 60, 30, 48, 14));
    }

    @Test
    void zeroCancellationNoticeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(0, 90, 60, 24, 21)));
        assertThat(exception.getMessage()).contains("cancellationNoticeHours");
    }

    @Test
    void negativeCreditValidityIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, -1, 60, 24, 21)));
        assertThat(exception.getMessage()).contains("singleCreditValidityDays");
    }

    @Test
    void slotStepOtherThan30Or60IsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 45, 24, 21)));
        assertThat(exception.getMessage()).contains("slotStepMinutes");
    }

    @Test
    void zeroMinBookingNoticeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 0, 21)));
        assertThat(exception.getMessage()).contains("minBookingNoticeHours");
    }

    @Test
    void zeroBookingHorizonIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 60, 24, 0)));
        assertThat(exception.getMessage()).contains("bookingHorizonDays");
    }

    @Test
    void invalidChangeLeavesSettingsUnchanged() {
        // arrange
        Practitioner practitioner = new Practitioner();
        practitioner.changeSettings(DEFAULTS);

        // act: the first four values are valid, the last one is not
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(48, 60, 30, 48, 0)));

        // assert
        assertThat(practitioner.settings()).isEqualTo(DEFAULTS);
    }

    @Test
    void nullSettingsAreRejected() {
        Practitioner practitioner = new Practitioner();

        Assertions.assertThrows(NullPointerException.class, () -> practitioner.changeSettings(null));
    }
}
