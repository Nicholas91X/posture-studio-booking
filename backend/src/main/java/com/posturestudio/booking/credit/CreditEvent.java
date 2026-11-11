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

    // the concessions of the practitioner (D-067, guide 40, part 6): the customer is told, never the note

    // a gift: a new lot of credits sessions
    record CreditGranted(long lotId, long customerId, long practitionerId, long serviceId, int credits,
                         LocalDate validUntil) implements CreditEvent {
    }

    // an extension: the last valid day moved forward
    record CreditExtended(long lotId, long customerId, long practitionerId, long serviceId, LocalDate oldValidUntil,
                          LocalDate newValidUntil) implements CreditEvent {
    }

    // a correction of amount (+N or -N); balance: what is left on the lot after it
    record CreditAdjusted(long lotId, long customerId, long practitionerId, long serviceId, int amount, long balance,
                          LocalDate validUntil) implements CreditEvent {
    }
}
