package com.posturestudio.booking.practitioner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

// Plain Java: no Spring, no database. The protected constructor is visible from the same package.
public class PractitionerTest {

    @Test
    void validSettingsAreApplied() {
        // arrange
        Practitioner practitioner = new Practitioner();

        // act
        practitioner.changeSettings(new PractitionerSettings(48, 60, 15));

        // assert
        assertThat(practitioner.settings()).isEqualTo(new PractitionerSettings(48, 60, 15));
    }

    @Test
    void zeroCancellationNoticeIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(0, 90, 30)));
        assertThat(exception.getMessage()).contains("cancellationNoticeHours");
    }

    @Test
    void negativeCreditValidityIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, -1, 30)));
        assertThat(exception.getMessage()).contains("singleCreditValidityDays");
    }

    @Test
    void zeroSlotStepIsRejected() {
        Practitioner practitioner = new Practitioner();

        var exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(24, 90, 0)));
        assertThat(exception.getMessage()).contains("slotStepMinutes");
    }

    @Test
    void invalidChangeLeavesSettingsUnchanged() {
        // arrange
        Practitioner practitioner = new Practitioner();
        practitioner.changeSettings(new PractitionerSettings(24, 90, 30));

        // act: the first two values are valid, the last one is not
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> practitioner.changeSettings(new PractitionerSettings(48, 60, 0)));

        // assert
        assertThat(practitioner.settings()).isEqualTo(new PractitionerSettings(24, 90, 30));
    }

    @Test
    void nullSettingsAreRejected() {
        Practitioner practitioner = new Practitioner();

        Assertions.assertThrows(NullPointerException.class, () -> practitioner.changeSettings(null));
    }
}
