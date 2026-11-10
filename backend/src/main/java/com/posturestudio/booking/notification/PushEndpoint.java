package com.posturestudio.booking.notification;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * An endpoint of a known push service, and nothing else (guide 28, part 7). The server will POST to this
 * address on its own behalf: accepting any URL would let a stolen panel account make it call localhost,
 * the Docker network or the metadata of the cloud (SSRF). Checked once, at the door: the rows in the
 * database are already checked. null is valid: NotBlank stops it.
 */
@Documented
@Constraint(validatedBy = PushEndpoint.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@interface PushEndpoint {

    // the push services of the browsers, and their subdomains. A new one: one line here and a test
    List<String> PUSH_SERVICES = List.of(
            "fcm.googleapis.com",           // Google: Chrome, Android, Opera, Samsung Internet
            "push.services.mozilla.com",    // Mozilla: Firefox
            "push.apple.com",               // Apple: Safari, iPhone, iPad
            "notify.windows.com");          // Microsoft: Edge

    int MAX_LENGTH = 2048;

    String message() default "must be the https address of a known push service";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PushEndpoint, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.length() > MAX_LENGTH) {
                return false;
            }
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                return false;
            }
            String host = uri.getHost();
            // https on the standard port, no user:password@ in front of the host
            return "https".equals(uri.getScheme())
                    && host != null
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && PUSH_SERVICES.stream().anyMatch(service -> host.equals(service) || host.endsWith("." + service));
        }
    }
}
