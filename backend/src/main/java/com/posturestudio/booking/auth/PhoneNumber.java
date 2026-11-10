package com.posturestudio.booking.auth;

import java.util.regex.Pattern;

/**
 * A phone number that can receive access codes (guide 29, part 2), always in E.164: +393331234567.
 * Like Email (D-020): the constructor normalizes and refuses, so a number inside the system is already right.
 *
 * Italian mobiles only (+39 3, then 8 or 9 digits): a landline gets no SMS, and foreign numbers are where
 * SMS pumping makes its money (guide 29, 5.1). A wider policy is a line here and its test; the database
 * checks only the E.164 format.
 */
public record PhoneNumber(String value) {

    // separators people type: spaces, dashes, dots, parentheses
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");
    private static final Pattern ITALIAN_MOBILE = Pattern.compile("\\+393\\d{8,9}");
    private static final Pattern NATIONAL_MOBILE = Pattern.compile("3\\d{8,9}");

    public PhoneNumber(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Phone number cannot be null or blank");
        }
        String number = SEPARATORS.matcher(value.strip()).replaceAll("");
        if (number.startsWith("00")) {
            number = "+" + number.substring(2);
        } else if (NATIONAL_MOBILE.matcher(number).matches()) {
            // no country code: an Italian mobile as people write it, 333 1234567
            number = "+39" + number;
        }
        if (!ITALIAN_MOBILE.matcher(number).matches()) {
            throw new IllegalArgumentException("Not an Italian mobile number");
        }
        this.value = number;
    }
}
