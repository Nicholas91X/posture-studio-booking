package com.posturestudio.booking.payment;

import java.net.URI;
import java.time.OffsetDateTime;

// The answer to "send a payment link" (guide 34): the Stripe page, the WhatsApp share address with the message
// ready, and until when the link is valid, in Rome
record PaymentLinkView(URI paymentUrl, URI whatsappUrl, OffsetDateTime expiresAt) {
}
