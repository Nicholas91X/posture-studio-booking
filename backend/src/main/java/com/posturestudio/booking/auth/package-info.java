/**
 * Module "auth": user accounts (app_user), access codes (login_code, guide 24), and from step 8 piece 2
 * sessions and roles.
 *
 * For the other modules: AccountService, which answers by user id (D-002, D-029).
 * This module depends on no other module: practitioner, customer and notification depend on it, never the
 * opposite. What it needs from outside it declares as an interface (LoginCodeSender, implemented by
 * notification), as schedule does with OccupiedTime (D-044).
 */
package com.posturestudio.booking.auth;
