package com.posturestudio.booking.practitioner;

/**
 * How the customers of a practitioner pay (D-089), decided by the payment account and nothing else: CARD with an
 * account assigned (paid in advance on Stripe, D-057), IN_STUDIO without (the customer books online and pays on the
 * day). A credit of the customer is used in both, as always (D-071).
 */
public enum PaymentMode {
    CARD,
    IN_STUDIO
}
