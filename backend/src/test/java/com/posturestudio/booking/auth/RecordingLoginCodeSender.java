package com.posturestudio.booking.auth;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// A LoginCodeSender that sends nothing and remembers what it was asked to send, so that a test can read the
// code "received by email" and check it. failNext() simulates a mail server that does not answer.
// Thread-safe: the concurrency test reads it from several threads
public final class RecordingLoginCodeSender implements LoginCodeSender {

    // code is null for the "account deactivated" email
    public record Sent(String to, String code) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    @Override
    public void sendCode(Email to, String code, Duration validity) {
        failIfAsked();
        sent.add(new Sent(to.value(), code));
    }

    @Override
    public void sendAccountDeactivated(Email to) {
        failIfAsked();
        sent.add(new Sent(to.value(), null));
    }

    private void failIfAsked() {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("simulated: the mail server does not answer");
        }
    }

    public void failNext() {
        failNext = true;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    // the code of the last email sent to this address
    public String lastCodeFor(String email) {
        return sent.reversed().stream()
                .filter(s -> s.to().equals(email))
                .findFirst()
                .map(Sent::code)
                .orElseThrow(() -> new AssertionError("No email sent to " + email));
    }

    public void clear() {
        sent.clear();
        failNext = false;
    }

    // @Primary: with @SpringBootTest the real EmailLoginCodeSender exists too, and this one must win
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        RecordingLoginCodeSender recordingLoginCodeSender() {
            return new RecordingLoginCodeSender();
        }
    }
}
