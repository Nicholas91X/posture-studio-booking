package com.posturestudio.booking.schedule;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A whole weekly schedule valid from a local date (D-034): what the panel sends to create
 * or replace a version. An empty list is allowed: "from this date I do not work".
 * The rules that depend on today (not in the past, date not taken) are in ScheduleService (409).
 */
@ValidBands
public record ScheduleVersionRequest(
        @NotNull LocalDate validFrom,
        // a generous limit: two bands a day for seven days are 14
        @NotNull @Size(max = 50) List<@NotNull @Valid WorkingBand> bands) {
}
