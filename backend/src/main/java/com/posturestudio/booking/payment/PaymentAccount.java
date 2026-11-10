package com.posturestudio.booking.payment;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Stripe account the studio collects on (D-057, guide 30, part 3): a name, kept in the database next to
 * every payment, and the two secrets of that account, from environment variables only.
 *
 * The compact constructor checks everything, so a wrong account cannot exist: a mistake stops the startup
 * (guide 30, part 4). The messages never contain a secret, only the name of the variable to fix.
 */
record PaymentAccount(String name, String secretKey, String webhookSecret) {

    // lower case letters and digits, starting with a letter: it ends up in an environment variable name and in
    // a column with the same check (V10). Never renamed: old payments carry it (guide 30, 3.4)
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9]{0,29}$");

    // a secret key (sk_) or a restricted one (rk_, recommended: least privilege, guide 30, 3.5), test or live
    private static final Pattern SECRET_KEY = Pattern.compile("^(sk|rk)_(test|live)_\\S+$");

    private static final Pattern WEBHOOK_SECRET = Pattern.compile("^whsec_\\S+$");

    PaymentAccount {
        Objects.requireNonNull(name, "the name of a payment account is required");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Payment account name '" + name
                    + "' is not valid: lower case letters and digits, starting with a letter, at most 30");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException(secretKeyVariable(name) + " is missing or empty");
        }
        if (!SECRET_KEY.matcher(secretKey).matches()) {
            throw new IllegalArgumentException(secretKeyVariable(name)
                    + " is not a Stripe secret or restricted key (sk_test_, sk_live_, rk_test_, rk_live_)");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException(webhookSecretVariable(name) + " is missing or empty");
        }
        if (!WEBHOOK_SECRET.matcher(webhookSecret).matches()) {
            throw new IllegalArgumentException(webhookSecretVariable(name) + " is not a Stripe webhook secret (whsec_)");
        }
    }

    // live keys move real money; test keys only test money (guide 30, 4.2)
    boolean live() {
        Matcher matcher = SECRET_KEY.matcher(secretKey);
        matcher.matches();
        return matcher.group(2).equals("live");
    }

    // the environment variables of an account, by convention (guide 30, 3.3): STRIPE_ROSSI_SECRET_KEY
    static String secretKeyVariable(String name) {
        return "STRIPE_" + name.toUpperCase(Locale.ROOT) + "_SECRET_KEY";
    }

    static String webhookSecretVariable(String name) {
        return "STRIPE_" + name.toUpperCase(Locale.ROOT) + "_WEBHOOK_SECRET";
    }

    // A record writes ALL its components in toString(): a log.info("{}", account) would print the keys.
    // Only the name, here (guide 30, 4.1)
    @Override
    public String toString() {
        return "PaymentAccount[" + name + "]";
    }
}
