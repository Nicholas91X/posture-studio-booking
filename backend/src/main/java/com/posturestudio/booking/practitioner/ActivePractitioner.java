package com.posturestudio.booking.practitioner;

/**
 * An active practitioner by name (D-091): who appears in the agendas compared. Nothing else: no email, no settings.
 */
public record ActivePractitioner(long id, String firstName, String lastName) {
}
