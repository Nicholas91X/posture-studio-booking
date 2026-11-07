package com.posturestudio.booking.practitioner;

/**
 * How the bookings made by customers are confirmed (D-030). Public: booking reads it from the settings.
 * Same values as practitioner_booking_approval_ck.
 */
public enum BookingApproval {

    // confirmed by the system right after payment: no request reaches the practitioner
    AUTOMATIC,

    // the practitioner approves or rejects within approval_window_hours, otherwise the request expires
    MANUAL
}
