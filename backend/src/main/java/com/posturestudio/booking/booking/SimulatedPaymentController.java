package com.posturestudio.booking.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PROVISIONAL (guide 20, part 5). TODO (step 9): remove this class; the Stripe webhook calls
 * BookingService.paymentAuthorized after checking the signature (D-007, D-031). A "done" criterion of step 9.
 *
 * "This booking has been paid", declared by whoever calls: it would let anyone confirm a booking without
 * paying. So it exists only when booking.simulated-payments is true, false by default: forgetting
 * something leaves it OFF. Without the property this URL is unknown (404), and a test proves it.
 * Locally: BOOKING_SIMULATED_PAYMENTS=true in .env.
 */
@RestController
@RequestMapping("/api/dev/bookings")
@ConditionalOnProperty(name = "booking.simulated-payments", havingValue = "true")
class SimulatedPaymentController {

    private final BookingService bookings;

    SimulatedPaymentController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping("/{id}/payment-authorized")
    CustomerBookingView paymentAuthorized(@PathVariable long id) {
        return bookings.paymentAuthorized(id);
    }
}
