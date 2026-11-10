package com.posturestudio.booking.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// The body of PUT .../payment-account. The format here (400); whether it is configured in the service (409)
record AccountAssignment(
        @NotNull @Pattern(regexp = "^[a-z][a-z0-9]{0,29}$") String account) {
}
