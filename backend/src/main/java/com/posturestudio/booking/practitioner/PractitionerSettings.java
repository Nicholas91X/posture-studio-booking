package com.posturestudio.booking.practitioner;

/**
 * The settings a practitioner changes from the panel. Leaves the module instead of the entity.
 */
public record PractitionerSettings(
        int cancellationNoticeHours,
        int singleCreditValidityDays,
        int slotStepMinutes) {
}
