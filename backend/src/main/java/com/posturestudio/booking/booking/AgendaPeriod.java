package com.posturestudio.booking.booking;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * ?from=2026-11-09&to=2026-11-15: the days of Rome of the agendas compared (D-091). At most 31 days: what is asked
 * is the work of the query, and a week or a month is what the panel shows. Reported on "period".
 */
public record AgendaPeriod(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    static final int MAX_DAYS = 31;

    @Schema(hidden = true)
    @AssertTrue(message = "from not after to, and at most 31 days")
    public boolean isPeriod() {
        return validSpan(from, to);
    }

    static boolean validSpan(LocalDate from, LocalDate to) {
        return from == null || to == null || (!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) < MAX_DAYS);
    }
}
