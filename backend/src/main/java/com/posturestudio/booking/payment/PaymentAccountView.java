package com.posturestudio.booking.payment;

import java.util.List;

// A configured account for the admin (guide 30, 9.1): its name and who collects on it. Never a key
record PaymentAccountView(String name, List<Long> practitionerIds) {

    PaymentAccountView {
        practitionerIds = List.copyOf(practitionerIds);
    }
}
