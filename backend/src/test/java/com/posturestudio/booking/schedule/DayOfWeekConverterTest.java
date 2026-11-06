package com.posturestudio.booking.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;

import org.junit.jupiter.api.Test;

public class DayOfWeekConverterTest {

    private final DayOfWeekConverter converter = new DayOfWeekConverter();

    @Test
    void mondayIsOneAndSundayIsSeven() {
        assertThat(converter.convertToDatabaseColumn(DayOfWeek.MONDAY)).isEqualTo((short) 1);
        assertThat(converter.convertToDatabaseColumn(DayOfWeek.SUNDAY)).isEqualTo((short) 7);
    }

    @Test
    void numbersAreReadBack() {
        assertThat(converter.convertToEntityAttribute((short) 1)).isEqualTo(DayOfWeek.MONDAY);
        assertThat(converter.convertToEntityAttribute((short) 7)).isEqualTo(DayOfWeek.SUNDAY);
    }

    @Test
    void nullStaysNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
