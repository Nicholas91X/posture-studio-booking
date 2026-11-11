package com.posturestudio.booking.credit;

import java.time.LocalDate;

/**
 * The lot a package payment produced (guide 37, part 5): its id, how many credits were born, the last day they
 * can be used. What the customer sees of a purchase once it is paid; the balance comes with piece 6.
 */
public record PackageLot(long paymentId, long lotId, int credits, LocalDate validUntil) {
}
