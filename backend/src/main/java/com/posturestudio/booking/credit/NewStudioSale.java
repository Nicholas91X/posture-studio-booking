package com.posturestudio.booking.credit;

import com.posturestudio.booking.customer.CustomerDetails;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * A package of the practitioner sold in the studio (D-089), for a customer found or created by email (D-048, D-066).
 * Sessions, validity and price are not here: they are taken from the package, as it is at this moment.
 */
record NewStudioSale(
        @NotNull Long packageOfferId,
        @NotNull @Valid CustomerDetails customer) {
}
