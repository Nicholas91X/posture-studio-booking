/**
 * Module "credit": lots and movements of the credits (step 10, D-009, D-010, D-066 to D-069, guide 36).
 * - a lot is a group of credits with the same origin and the same last valid day: a package bought, a gift of
 *   the practitioner (and from step 11 a session cancelled in time);
 * - the ledger: movements that are never changed, the balance is their sum;
 * - the last valid day is a calendar day in Europe/Rome, one rule in ValidUntil;
 * - an extension moves the last valid day forward, and leaves its row in credit_lot_extension.
 * The database defends the ledger by itself (V15): append-only movements, a lot that changes only its last
 * valid day and only with its trace, no lot below zero.
 *
 * - the concessions of the practitioner from the panel: gift, extension, correction (CreditConcessions, D-073).
 *
 * Other modules refer to lots, payments, bookings and accounts by id (D-026). booking and payment depend on
 * credit, never the other way; credit depends on catalog, customer and practitioner (for the concessions), none of
 * which depends on it.
 */
package com.posturestudio.booking.credit;
