package com.posturestudio.booking.booking;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.posturestudio.booking.customer.CustomerDetails;

/**
 * What the practitioner sends to book for a customer (D-024): the practitioner is the one acting, the
 * customer is found by email or created (guide 22, part 3). @Valid validates the customer's fields too,
 * reported as customer.email, customer.firstName...
 */
public record NewPanelBooking(
        @NotNull Long serviceId,
        @NotNull OffsetDateTime startsAt,
        @NotNull @Valid CustomerDetails customer) {
}
