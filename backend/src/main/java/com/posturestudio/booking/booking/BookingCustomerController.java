package com.posturestudio.booking.booking;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    BookingCustomerController(BookingService bookings, CurrentCustomer current) {
        this.bookings = bookings;
        this.current = current;
    }

    @PostMapping
    ResponseEntity<CustomerBookingView> request(@Valid @RequestBody NewBooking request) {
        CustomerBookingView created = bookings.request(current.id(), request);
        return ResponseEntity.created(URI.create("/api/customer/bookings/" + created.id())).body(created);
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
}
