package com.posturestudio.booking.notification;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

// A mail sender that sends nothing and remembers what it was asked to send, and in which thread (guide 27,
// part 9). failNextTo() simulates a mail server refusing one email. Thread-safe: the emails arrive from the
// virtual threads of NotificationConfiguration while the test reads them.
// A JavaMailSenderImpl and not just a MailSender: Spring Boot creates its own JavaMailSender only when no
// MailSender exists (@ConditionalOnMissingBean). A bare MailSender here made it back off, and EmailLoginCodeSender,
// which needs a JavaMailSender, was left with none. This one replaces the real sender for both
public final class RecordingMailSender extends JavaMailSenderImpl {

    public record Sent(String from, String to, String subject, String text, String thread, boolean virtual) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Set<String> failNextTo = ConcurrentHashMap.newKeySet();

    @Override
    public void send(SimpleMailMessage message) {
        String to = message.getTo()[0];
        if (failNextTo.remove(to)) {
            throw new MailSendException("simulated: the mail server refuses this email");
        }
        Thread thread = Thread.currentThread();
        sent.add(new Sent(message.getFrom(), to, message.getSubject(), message.getText(), thread.getName(),
                thread.isVirtual()));
    }

    @Override
    public void send(SimpleMailMessage... messages) {
        for (SimpleMailMessage message : messages) {
            send(message);
        }
    }

    public void failNextTo(String address) {
        failNextTo.add(address);
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public List<Sent> sentTo(String address) {
        return sent.stream().filter(email -> email.to().equals(address)).toList();
    }

    public void clear() {
        sent.clear();
        failNextTo.clear();
    }

    // the only mail sender of the context: Spring Boot's backs off when it sees this one
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }
}
