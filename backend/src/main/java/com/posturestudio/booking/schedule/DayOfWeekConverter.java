package com.posturestudio.booking.schedule;

import java.time.DayOfWeek;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// DayOfWeek <-> smallint 1..7, the ISO numbering of java.time (Monday = 1), as in V3.
// @Enumerated(ORDINAL) would store 0..6, and the check of V3 would reject Monday
@Converter
class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {

    @Override
    public Short convertToDatabaseColumn(DayOfWeek day) {
        return day == null ? null : (short) day.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Short value) {
        return value == null ? null : DayOfWeek.of(value);
    }
}
