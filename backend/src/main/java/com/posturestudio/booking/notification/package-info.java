/**
 * Module "notification": emails, and from step 8 piece 5 push notifications (D-032, D-039).
 *
 * It implements what other modules declare they need (LoginCodeSender of auth), so the dependencies go
 * one way: notification -> auth, never the opposite (guide 24, part 7).
 * From piece 4 it listens to the events of booking (BookingNotifications, guide 27): after the commit, in
 * a virtual thread of its own (NotificationConfiguration), with the texts decided by BookingEmails, a pure
 * function. It asks customer, practitioner and catalog for names and addresses; nobody depends on it.
 */
package com.posturestudio.booking.notification;
