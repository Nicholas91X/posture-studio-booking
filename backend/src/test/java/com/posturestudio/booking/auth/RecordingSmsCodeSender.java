package com.posturestudio.booking.auth;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// An SmsCodeSender that sends nothing and remembers what it was asked to send (guide 29, part 10), as
// RecordingLoginCodeSender for the email. Synchronous: the tests read the code right after the request
public final class RecordingSmsCodeSender implements SmsCodeSender {

    public record Sent(String to, String code, CodePurpose purpose) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendCode(PhoneNumber to, String code, Duration validity, CodePurpose purpose) {
        sent.add(new Sent(to.value(), code, purpose));
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    // the code of the last SMS sent to this number for this purpose
    public String lastCodeFor(String phone, CodePurpose purpose) {
        return sent.reversed().stream()
                .filter(s -> s.to().equals(phone) && s.purpose() == purpose)
                .findFirst()
                .map(Sent::code)
                .orElseThrow(() -> new AssertionError("No " + purpose + " SMS sent to " + phone));
    }

    public void clear() {
        sent.clear();
    }

    // the only SmsCodeSender of the context: with none, the SMS APIs answer 503
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        RecordingSmsCodeSender recordingSmsCodeSender() {
            return new RecordingSmsCodeSender();
        }
    }
}
