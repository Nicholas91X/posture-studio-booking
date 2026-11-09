package com.posturestudio.booking.auth;

/**
 * The outcome of checking an access code (guide 24, part 4). A result and not an exception: a wrong code
 * is a normal outcome, and above all an exception would roll back the transaction together with the
 * attempt just counted, so the limit of 5 attempts would never be reached (guide 24, 4.1).
 *
 * sealed: these are all the cases. A switch over a CodeCheck lists them with no default, and stops
 * compiling if a case is added (as BookingEvent, D-046). With record patterns (Java 21) a case can also
 * take the fields apart: case Valid(long userId, boolean newAccount) -> ...
 */
public sealed interface CodeCheck {

    // the right code. newAccount: the email was never seen, the CUSTOMER account was born now (guide 24, 5.2)
    record Valid(long userId, boolean newAccount) implements CodeCheck {
    }

    // a wrong code, with attempts left: the person can try again
    record Wrong() implements CodeCheck {
    }

    // no code to check: never requested, expired, used, or 5 wrong attempts. A new code is needed
    record Expired() implements CodeCheck {
    }

    // the right code, but the account is deactivated: only who owns the email can see this (guide 24, 5.4)
    record AccountDeactivated() implements CodeCheck {
    }
}
