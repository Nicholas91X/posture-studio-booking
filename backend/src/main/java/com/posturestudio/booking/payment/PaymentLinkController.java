package com.posturestudio.booking.payment;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.posturestudio.booking.practitioner.CurrentPractitioner;

/**
 * "Send a payment link" from the panel (D-059, D-065, guide 34). Under /api/panel/** (only the PRACTITIONER, D-051),
 * in the payment module because the link is its data, as the admin API of the accounts (D-060).
 * A POST: every call makes a new link and invalidates the previous one.
 */
@RestController
class PaymentLinkController {

    private final PaymentLinks links;
    private final CurrentPractitioner current;

    PaymentLinkController(PaymentLinks links, CurrentPractitioner current) {
        this.links = links;
        this.current = current;
    }

    @PostMapping("/api/panel/bookings/{id}/payment-link")
    PaymentLinkView create(@PathVariable long id) {
        return links.create(current.id(), id);
    }
}
