package com.posturestudio.booking.payment;

/**
 * What the customer sees of a purchase (guide 37, part 5). Public: its names are part of the API contract.
 */
public enum PackagePurchaseStatus {

    // the Stripe page is still valid, or it was just paid and the notice of Stripe has not arrived yet
    PENDING,

    // paid: the lot is born
    PAID,

    // the page expired unpaid: by the notice of Stripe, or by the clock when the notice is late
    EXPIRED
}
