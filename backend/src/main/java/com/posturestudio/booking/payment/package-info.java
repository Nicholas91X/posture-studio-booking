/**
 * Module "payment": everything that talks to Stripe (step 9, D-057 to D-065, guides 30 to 35).
 * - accounts: the Stripe accounts declared in the configuration (PaymentAccounts), the one assigned to each
 *   practitioner by the admin, the checks at startup (piece 1);
 * - the payment of a request: a Checkout session with manual capture, created in the transaction of the
 *   request (StripeCardCheckout, booking's CardCheckout, piece 2);
 * - the webhook: one address per account, the signature, each event processed once (StripeWebhookController,
 *   StripeWebhookService, piece 3);
 * - capture at approval and release otherwise, on the account of the payment, with the reconciliation
 *   (StripeCardHolds, booking's CardHolds, piece 4), and the releases tried again by a job (PaymentJobs);
 * - the payment link of a session paid in the studio (PaymentLinks, piece 5);
 * - from step 10, the purchase of a package, captured at once, whose webhook creates the lot of credits
 *   (PackagePurchases, StripeWebhookService.packagePaid, guide 37).
 * Stripe itself is behind StripeGateway: the real StripeClientGateway, a fake in every test context.
 *
 * Dependencies go one way: payment -> practitioner, booking, catalog, customer, credit. Nobody depends on payment: when
 * booking needs it, booking declares an interface in its own words and payment implements it (D-044, D-046).
 */
package com.posturestudio.booking.payment;
