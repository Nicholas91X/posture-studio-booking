package com.posturestudio.booking.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email
 */
public record Email(String value) {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public Email(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Email cannot be null or blank");
        }
        String normalizedValue = value.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalizedValue).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
        this.value = normalizedValue;
    }
}
