package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import com.posturestudio.booking.auth.ValidEmail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * ?serviceId=12&startsAt=2026-11-10T09:00:00%2B01:00&email=mario@example.com: would a booking from the panel use a
 * credit of this customer (D-090)? The query as a record, as SlotRange: one place for the validation, 400 on the field.
 */
public record PanelCreditQuery(
        @NotNull Long serviceId,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
        @NotBlank @ValidEmail String email) {
}
