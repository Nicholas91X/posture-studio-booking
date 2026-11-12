package com.posturestudio.booking.booking;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CurrentUser;

/**
 * Admin API for the sessions of a practitioner (D-077, guide 45, part 5): the urgency of a practitioner who cannot
 * be reached. The admin sees what stops the deactivation and makes the cancellation of the studio on their behalf,
 * with the same code as the panel (D-080); the trace says who did it (cancelled_by is the admin's account).
 * Only the ADMIN gets here (the rule of the /api/admin area, D-051). In booking because the sessions are its data.
 */
@RestController
@RequestMapping("/api/admin/practitioners/{practitionerId}/bookings")
class BookingAdminController {

    private final BookingService bookings;
    private final CurrentUser user;

    BookingAdminController(BookingService bookings, CurrentUser user) {
        this.bookings = bookings;
        this.user = user;
    }

    // the sessions to come that stop the deactivation, the soonest first
    @GetMapping
    List<AdminBookingView> unfinished(@PathVariable long practitionerId) {
        return bookings.unfinishedForAdmin(practitionerId);
    }

    // the cancellation of the studio, signed by the admin: the session of another practitioner is not found
    @PostMapping("/{bookingId}/cancel")
    PanelCancellationView cancel(@PathVariable long practitionerId, @PathVariable long bookingId) {
        return bookings.cancelByStudio(practitionerId, user.id(), bookingId);
    }
}
