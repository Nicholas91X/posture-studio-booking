package com.posturestudio.booking.booking;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.auth.CurrentUser;
import com.posturestudio.booking.customer.CurrentCustomer;

import jakarta.validation.Valid;

/**
 * Customer API for bookings, under /api/customer as the practitioner's is under /api/panel.
 * Nothing about bookings lives under /api/public (D-029).
 * No logic here: who is acting, validation of the shape, the service, the HTTP status.
 */
@RestController
@RequestMapping("/api/customer/bookings")
class BookingCustomerController {

    private final BookingService bookings;
    private final CurrentCustomer current;
    // the account that acts, for the trace of a cancellation (V16), as the concessions of the panel (D-073)
    private final CurrentUser user;

    BookingCustomerController(BookingService bookings, CurrentCustomer current, CurrentUser user) {
        this.bookings = bookings;
        this.current = current;
        this.user = user;
    }

    @PostMapping
    ResponseEntity<CustomerBookingView> request(@Valid @RequestBody NewBooking request) {
        CustomerBookingView created = bookings.request(current.id(), request);
        return ResponseEntity.created(URI.create("/api/customer/bookings/" + created.id())).body(created);
    }

    // "my sessions": all of them, the furthest in the future first; ?period=upcoming (not over, the soonest first) or
    // ?period=past (over, the latest first), guide 46, part 4. An unknown value is a 400
    @GetMapping
    List<CustomerBookingView> list(@RequestParam(required = false) String period) {
        return bookings.listForCustomer(current.id(), period == null ? null : BookingPeriod.fromQuery(period));
    }

    // the page back from the payment asks for the status here (D-007)
    @GetMapping("/{id}")
    CustomerBookingView find(@PathVariable long id) {
        return bookings.findForCustomer(current.id(), id);
    }

    // while waiting for the practitioner's answer: nothing charged (D-030)
    @PostMapping("/{id}/withdraw")
    CustomerBookingView withdraw(@PathVariable long id) {
        return bookings.withdraw(current.id(), id);
    }

    // a confirmed session: in time the credit comes back, late it is lost (guide 43). Repeated, the same answer
    @PostMapping("/{id}/cancel")
    CustomerBookingView cancel(@PathVariable long id) {
        return bookings.cancel(current.id(), user.id(), id);
    }
}
