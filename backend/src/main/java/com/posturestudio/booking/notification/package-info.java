/**
 * Module "notification": emails, and from step 8 piece 5 push notifications (D-032, D-039).
 *
 * It implements what other modules declare they need (LoginCodeSender of auth), so the dependencies go
 * one way: notification -> auth, never the opposite (guide 24, part 7). From piece 4 it also listens to
 * the events of booking.
 */
package com.posturestudio.booking.notification;
