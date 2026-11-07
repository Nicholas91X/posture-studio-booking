package com.posturestudio.booking.booking;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

import jakarta.validation.Valid;

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

    // for a customer of theirs, paid in the studio (D-024, D-040)
    @PostMapping
    ResponseEntity<PanelBookingView> book(@Valid @RequestBody NewPanelBooking request) {
        PanelBookingView created = bookings.bookFromPanel(current.id(), request);
        return ResponseEntity.created(URI.create("/api/panel/bookings/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    PanelBookingView find(@PathVariable long id) {
        return bookings.findForPractitioner(current.id(), id);
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
