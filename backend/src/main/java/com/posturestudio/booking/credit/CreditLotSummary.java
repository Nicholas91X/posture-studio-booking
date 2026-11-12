package com.posturestudio.booking.credit;

import java.time.LocalDate;

/**
 * A lot in a list (guide 41, part 1): whose and for what (ids, as a booking: the frontend has the names from the
 * public catalog), its balance, its last valid day and its state, decided here with the day of Rome
 */
record CreditLotSummary(long id, long practitionerId, long serviceId, LotSource source, long balance,
                        LocalDate validUntil, Status status) {

    enum Status {
        // credits left, and the last valid day not gone: usable for a session until that day (D-071)
        USABLE,
        // nothing left, whatever the day
        USED_UP,
        // credits left, but the last valid day is gone: the case for an extension (D-067)
        EXPIRED,
        // credits left and in time, but the practitioner is deactivated: not usable (D-077). Computed, not stored:
        // a reactivation makes the lot USABLE again by itself
        PRACTITIONER_UNAVAILABLE
    }

    // the order of the rules (guide 45, part 7): used up, then expired (it would be anyway), then the practitioner
    static CreditLotSummary of(CreditLot lot, long balance, LocalDate today, boolean practitionerActive) {
        LocalDate lastDay = lot.getValidUntil().lastDay();
        Status status = balance <= 0 ? Status.USED_UP
                : lastDay.isBefore(today) ? Status.EXPIRED
                : !practitionerActive ? Status.PRACTITIONER_UNAVAILABLE
                : Status.USABLE;
        return new CreditLotSummary(lot.getId(), lot.getPractitionerId(), lot.getServiceId(), lot.getSource(), balance,
                lastDay, status);
    }
}
