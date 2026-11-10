package com.posturestudio.booking.notification;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.auth.CodePurpose;
import com.posturestudio.booking.auth.PhoneNumber;
import com.posturestudio.booking.auth.SmsCodeSender;

/**
 * DEVELOPMENT ONLY (guide 29, 6.3): an "SMS" that is an email to Mailpit, at sms-393331234567@sms.local, with
 * the exact text of the SMS. It lets the SMS sign-in be tried by hand without a provider, and without a code
 * in the log (guide 24, 7.4). On only with notification.sms.mailpit=true (SMS_TO_MAILPIT); off by default.
 * Forgotten on in production, it would hand the SMS to the email provider towards a domain that does not
 * exist: no SMS would arrive, no code would be exposed.
 *
 * @Async, in a virtual thread of the notification executor, as a real provider will: the answer of the
 * request never waits for the delivery (guide 29, 4.2). A failure is logged here, never thrown.
 */
@Component
@ConditionalOnProperty(name = "notification.sms.mailpit", havingValue = "true")
class MailpitSmsCodeSender implements SmsCodeSender {

    private static final Logger log = LoggerFactory.getLogger(MailpitSmsCodeSender.class);

    private final SmsTexts texts;
    private final MailSender mail;
    private final String from;

    MailpitSmsCodeSender(SmsTexts texts, MailSender mail, @Value("${notification.mail.from}") String from) {
        this.texts = texts;
        this.mail = mail;
        this.from = from;
    }

    @Override
    @Async(NotificationConfiguration.EXECUTOR)
    public void sendCode(PhoneNumber to, String code, Duration validity, CodePurpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mailboxOf(to));
        message.setSubject("SMS a " + to.value());
        message.setText(texts.text(code, validity, purpose));
        try {
            mail.send(message);
        } catch (RuntimeException e) {
            log.error("SMS ({}) not delivered to Mailpit", purpose, e);
        }
    }

    // +393331234567 -> sms-393331234567@sms.local: one mailbox per number in the Mailpit page
    static String mailboxOf(PhoneNumber to) {
        return "sms-" + to.value().substring(1) + "@sms.local";
    }
}
