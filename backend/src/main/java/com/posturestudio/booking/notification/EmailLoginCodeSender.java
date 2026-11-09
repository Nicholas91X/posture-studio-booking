package com.posturestudio.booking.notification;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.posturestudio.booking.auth.Email;
import com.posturestudio.booking.auth.LoginCodeSender;

/**
 * The access code by email (guide 24, part 7): the first email of the system. In development it goes to
 * Mailpit (infra/docker-compose.dev.yml, http://localhost:8025); the real provider comes before step 14.
 *
 * Written for someone who did not ask for it: an email typed wrong reaches a stranger. Nothing but the
 * code, never a name, nothing about health. The code is not in the subject, because a locked phone shows it.
 * Plain text, as text blocks (Java 15): the string is written as it will look, with no "\n" and no "+".
 */
@Component
class EmailLoginCodeSender implements LoginCodeSender {

    private final JavaMailSender mail;
    private final String from;

    EmailLoginCodeSender(JavaMailSender mail, @Value("${notification.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void sendCode(Email to, String code, Duration validity) {
        send(to, "Il tuo codice di accesso", """
                Il tuo codice di accesso è: %s

                Vale %d minuti e si può usare una volta sola.

                Se non l'hai chiesto tu, ignora questa email: senza il codice nessuno può entrare.
                """.formatted(code, validity.toMinutes()));
    }

    @Override
    public void sendAccountDeactivated(Email to) {
        send(to, "Il tuo account è disattivato", """
                Hai chiesto di accedere, ma il tuo account è disattivato.
                Per riattivarlo, contatta lo studio.

                Se non l'hai chiesto tu, ignora questa email.
                """);
    }

    // a MailException (unchecked) if the mail server does not answer: the caller turns it into a 503
    private void send(Email to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.value());
        message.setSubject(subject);
        message.setText(text);
        mail.send(message);
    }
}
