package com.posturestudio.booking.booking;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer of the credit check (D-090): whether a credit of the customer WITH THIS PRACTITIONER would pay the
 * session, and its last valid day. Nothing about the other practitioner, nothing about the customer.
 */
public record PanelCreditCheck(boolean usableCredit, @Schema(nullable = true) LocalDate validUntil) {
}
