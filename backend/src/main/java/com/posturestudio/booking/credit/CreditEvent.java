package com.posturestudio.booking.credit;

import java.time.LocalDate;

/**
 * What happens to credits, for whoever listens after the commit (notification, D-053). Only ids, numbers and
 * dates: names and addresses are asked of their modules. sealed, as BookingEvent: a listener's switch covers
 * every kind with no default, and a new one stops it compiling until someone decides what it sends.
 * Published inside the transaction that wrote the lot: a rolled back transaction announces nothing.
 */
public sealed interface CreditEvent {

    long lotId();

    long customerId();

    long practitionerId();

    // the service the credits are for
    long serviceId();

    // a package was paid and its lot is born (guide 37, part 10)
    record PackagePurchased(long lotId, long customerId, long practitionerId, long serviceId, int credits,
                            LocalDate validUntil) implements CreditEvent {
    }
}
