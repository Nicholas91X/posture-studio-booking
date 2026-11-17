package com.posturestudio.booking.practitioner;

/**
 * What anyone sees of a practitioner without signing in (D-029, D-084): id and name, and what the customer must know
 * BEFORE choosing a time:
 * - paymentMode: CARD with a payment account (paid in advance by card, D-057), IN_STUDIO without (booked online and
 *   paid on the day, D-089). The site says it before the customer chooses a time;
 * - bookingApproval and approvalWindowHours: who confirms, and how long the practitioner may take to answer, which
 *   the customer sees before booking (D-030);
 * - cancellationNoticeHours: until how many hours before the start a cancellation is in time (D-015). Information
 *   only: the notice of a booking is photographed at booking time, and decided by the backend at the click (D-079).
 * A record of its own, not shared with the panel: a field added for the panel must never reach the public API by
 * accident. The frontend composes the name to show.
 */
public record PublicPractitioner(long id, String firstName, String lastName, PaymentMode paymentMode,
                                 BookingApproval bookingApproval, int approvalWindowHours, int cancellationNoticeHours) {

    static PublicPractitioner from(Practitioner practitioner, boolean onlineBooking) {
        PractitionerSettings settings = practitioner.settings();
        return new PublicPractitioner(practitioner.getId(), practitioner.getFirstName(), practitioner.getLastName(),
                onlineBooking ? PaymentMode.CARD : PaymentMode.IN_STUDIO, settings.bookingApproval(), settings.approvalWindowHours(),
                settings.cancellationNoticeHours());
    }
}
