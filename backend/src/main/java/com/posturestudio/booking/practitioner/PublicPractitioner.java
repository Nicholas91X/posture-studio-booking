package com.posturestudio.booking.practitioner;

/**
 * What anyone sees of a practitioner without signing in (D-029, D-084): id and name, and what the customer must know
 * BEFORE choosing a time:
 * - onlineBooking: the practitioner has a payment account, so a session can be paid by card (D-057). false: only a
 *   customer with one of their credits can book (D-071); the site says so first, not with an error at the end;
 * - bookingApproval and approvalWindowHours: who confirms, and how long the practitioner may take to answer, which
 *   the customer sees before booking (D-030);
 * - cancellationNoticeHours: until how many hours before the start a cancellation is in time (D-015). Information
 *   only: the notice of a booking is photographed at booking time, and decided by the backend at the click (D-079).
 * A record of its own, not shared with the panel: a field added for the panel must never reach the public API by
 * accident. The frontend composes the name to show.
 */
public record PublicPractitioner(long id, String firstName, String lastName, boolean onlineBooking,
                                 BookingApproval bookingApproval, int approvalWindowHours, int cancellationNoticeHours) {

    static PublicPractitioner from(Practitioner practitioner, boolean onlineBooking) {
        PractitionerSettings settings = practitioner.settings();
        return new PublicPractitioner(practitioner.getId(), practitioner.getFirstName(), practitioner.getLastName(),
                onlineBooking, settings.bookingApproval(), settings.approvalWindowHours(),
                settings.cancellationNoticeHours());
    }
}
