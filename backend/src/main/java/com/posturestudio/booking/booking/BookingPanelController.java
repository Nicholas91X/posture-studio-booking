package com.posturestudio.booking.booking;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

/**
 * Panel API for the bookings of the practitioner who is acting. Answers are actions, as deactivate
 * in step 5 (D-028): a booking is not edited, it takes a step of the state machine.
 * No logic here: who is acting, the service, the HTTP status.
 */
@RestController
@RequestMapping("/api/panel/bookings")
class BookingPanelController {

    private final BookingService bookings;
    private final CurrentPractitioner current;

    BookingPanelController(BookingService bookings, CurrentPractitioner current) {
        this.bookings = bookings;
        this.current = current;
    }

    @PostMapping("/{id}/approve")
    PanelBookingView approve(@PathVariable long id) {
        return bookings.approve(current.id(), id);
    }

    @PostMapping("/{id}/reject")
    PanelBookingView reject(@PathVariable long id) {
        return bookings.reject(current.id(), id);
    }
}
