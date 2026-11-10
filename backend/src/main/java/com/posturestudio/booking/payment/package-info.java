/**
 * Module "payment": the Stripe accounts the studio collects on, the payments, and later (step 9) Stripe
 * Checkout, the webhook and the implementation of booking's CardHolds.
 *
 * Step 9, piece 1 (D-057, guide 30): the payment accounts declared in the configuration (PaymentAccounts),
 * the account assigned to each practitioner by the admin, the table payment, and the startup check.
 *
 * Dependencies go one way: payment -> practitioner, payment -> booking. Nobody depends on payment: when
 * booking needs it, booking declares an interface in its own words and payment implements it (D-044, D-046).
 */
package com.posturestudio.booking.payment;
