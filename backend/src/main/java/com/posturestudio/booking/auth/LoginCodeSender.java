package com.posturestudio.booking.auth;

import java.time.Duration;

/**
 * What auth needs to deliver an access code, said with the types of auth (guide 24, part 7).
 * Implemented by the notification module: auth depends on no module, and the dependency goes one way,
 * notification -> auth. The same trick as OccupiedTime (D-044) and CardHolds (D-046).
 *
 * Both methods are called after the commit, outside any transaction. A failure is an exception: the
 * request answers 503, and the person tries again.
 */
public interface LoginCodeSender {

    void sendCode(Email to, String code, Duration validity);

    // instead of the code, to a deactivated account: only the owner of the mailbox reads it (guide 24, 5.4)
    void sendAccountDeactivated(Email to);
}
