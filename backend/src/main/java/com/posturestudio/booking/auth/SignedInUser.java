package com.posturestudio.booking.auth;

/**
 * Who is acting in this request: the principal of the Authentication that SessionAccounts builds on every
 * request from the account in the database (guide 25, 4.2). Never stored in the session, so never
 * serialized: the session holds only the user id.
 */
record SignedInUser(long id, Role role) {
}
