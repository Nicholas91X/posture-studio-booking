package com.posturestudio.booking.booking;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CurrentUser;
import com.posturestudio.booking.practitioner.CurrentPractitioner;
import com.posturestudio.booking.schedule.SlotRange;

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
    // the account that acts, for the trace of a cancellation (V16), as the concessions (D-073)
    private final CurrentUser user;

    BookingPanelController(BookingService bookings, CurrentPractitioner current, CurrentUser user) {
        this.bookings = bookings;
        this.current = current;
        this.user = user;
    }

    // for a customer of theirs, paid in the studio (D-024, D-040)
    @PostMapping
    ResponseEntity<PanelBookingView> book(@Valid @RequestBody NewPanelBooking request) {
        PanelBookingView created = bookings.bookFromPanel(current.id(), request);
        return ResponseEntity.created(URI.create("/api/panel/bookings/" + created.id())).body(created);
    }

    // ?from=2026-11-09&to=2026-11-15: the same SlotRange of the free slots (both required, from not after to)
    @GetMapping
    List<PanelBookingRow> list(@Valid SlotRange period) {
        return bookings.listForPanel(current.id(), period.from(), period.to());
    }

    // a literal path wins over /{id}
    @GetMapping("/requests")
    List<PanelBookingRow> requests() {
        return bookings.requestsForPanel(current.id());
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

    // a future session of theirs, confirmed or in wait: the customer loses nothing (D-075, guide 44)
    @PostMapping("/{id}/cancel")
    PanelCancellationView cancel(@PathVariable long id) {
        return bookings.cancelByStudio(current.id(), user.id(), id);
    }
}
