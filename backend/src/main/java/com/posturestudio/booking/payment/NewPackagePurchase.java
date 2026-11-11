package com.posturestudio.booking.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// What the customer asks: which package (guide 37, part 2). Price, sessions and validity come from the
// catalog, never from the request (D-005: the frontend shows and sends, the backend decides)
record NewPackagePurchase(@NotNull @Positive Long packageOfferId) {
}
