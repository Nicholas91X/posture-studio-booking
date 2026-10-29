package com.posturestudio.booking.shared;

/**
 * Guards used by entities to protect their own state. A failure here is a programming error:
 * request validation should have stopped the value earlier.
 */
public final class Checks {

    private Checks() {
    }

    public static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
        return value;
    }

    public static long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
        return value;
    }

    public static int requireAtLeast(int value, int min, String field) {
        if (value < min) {
            throw new IllegalArgumentException(field + " must be at least " + min + ", was " + value);
        }
        return value;
    }

    // returns the value without surrounding whitespace
    public static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
