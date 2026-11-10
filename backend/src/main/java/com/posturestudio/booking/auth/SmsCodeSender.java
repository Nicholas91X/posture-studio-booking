package com.posturestudio.booking.auth;

import java.time.Duration;

/**
 * What auth needs to deliver a code by SMS (guide 29, part 6), said with the types of auth, as LoginCodeSender
 * for the email. Implemented by notification: today only MailpitSmsCodeSender, in development; the real
 * provider is an open point before step 14. Optional: with no implementation the SMS APIs answer 503
 * SMS_UNAVAILABLE.
 *
 * Called after the commit. Unlike the email, an implementation delivers in another thread and does not
 * throw: the answer must not wait for the provider, or its duration would tell which numbers are known
 * (guide 29, 4.2). A failure is logged where it happens.
 */
public interface SmsCodeSender {

    void sendCode(PhoneNumber to, String code, Duration validity, CodePurpose purpose);
}
