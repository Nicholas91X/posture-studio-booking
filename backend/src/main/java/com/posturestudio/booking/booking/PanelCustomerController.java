package com.posturestudio.booking.booking;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

/**
 * "My customers" of the panel (D-066, guide 41, part 4), under /api/panel: only practitioners (D-051). In the
 * booking module, which can ask bookings, credits and customers without a cycle. The credits of one customer are
 * in the credit module, at /api/panel/customers/{id}/credits.
 */
@RestController
@RequestMapping("/api/panel/customers")
class PanelCustomerController {

    private final BookingService bookings;
    private final CurrentPractitioner current;

    PanelCustomerController(BookingService bookings, CurrentPractitioner current) {
        this.bookings = bookings;
        this.current = current;
    }

    @GetMapping
    List<PanelCustomerView> list() {
        return bookings.customersForPanel(current.id());
    }
}
