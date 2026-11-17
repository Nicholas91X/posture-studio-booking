package com.posturestudio.booking.booking;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ?from=&to=&durationMinutes=60&practitionerIds=1&practitionerIds=2: the windows in which all these practitioners
 * are free together, at least durationMinutes long (D-091). The days as in AgendaPeriod.
 */
public record FreeTogetherQuery(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @NotNull @Positive @Max(480) Integer durationMinutes,
        @NotEmpty @Size(max = 10) List<@NotNull Long> practitionerIds) {

    @Schema(hidden = true)
    @AssertTrue(message = "from not after to, and at most 31 days")
    public boolean isPeriod() {
        return AgendaPeriod.validSpan(from, to);
    }
}
