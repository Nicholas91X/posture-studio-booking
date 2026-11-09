/**
 * Module "auth": user accounts (app_user), access codes (login_code, guide 24), sessions and the rules
 * of access by role (guide 25: SecurityConfiguration, SessionAccounts, SessionController).
 *
 * For the other modules: AccountService, which answers by user id (D-002, D-029), and CurrentUser, who is
 * signed in for the current request.
 * This module depends on no other module: practitioner, customer and notification depend on it, never the
 * opposite. What it needs from outside it declares as an interface (LoginCodeSender, implemented by
 * notification), as schedule does with OccupiedTime (D-044).
 */
package com.posturestudio.booking.auth;
