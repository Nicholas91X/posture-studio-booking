package com.posturestudio.booking.payment;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;

/**
 * The payment accounts of this server, read once at startup (D-057, guide 30, part 3).
 *
 * PAYMENT_ACCOUNTS lists the names; the secrets of each account come from STRIPE_<NAME>_SECRET_KEY and
 * STRIPE_<NAME>_WEBHOOK_SECRET, asked to Spring's Environment. The Environment is the union of every source
 * (the .env imported as a properties file in development, real environment variables in production), so
 * the same question finds the key in both cases: adding an account is three lines and a restart (3.3).
 *
 * Immutable, built by PaymentConfiguration. Zero accounts is allowed (4.4): nobody can be assigned one.
 */
final class PaymentAccounts {

    // SequencedMap (Java 21): a map with a defined order, here the order of PAYMENT_ACCOUNTS, so the admin
    // sees the accounts as they are written. LinkedHashMap has always kept it; now the type says so
    private final SequencedMap<String, PaymentAccount> byName;

    private PaymentAccounts(SequencedMap<String, PaymentAccount> byName) {
        this.byName = Collections.unmodifiableSequencedMap(byName);
    }

    /**
     * @param names     the value of PAYMENT_ACCOUNTS, comma separated, possibly empty
     * @param live      payment.live: true only in production; every key must match it (guide 30, 4.2)
     * @param variables how to read an environment variable (Environment::getProperty; a Map::get in the tests)
     */
    static PaymentAccounts from(String names, boolean live, Function<String, String> variables) {
        SequencedMap<String, PaymentAccount> accounts = new LinkedHashMap<>();
        for (String name : parseNames(names)) {
            if (accounts.containsKey(name)) {
                throw new IllegalStateException("Payment account '" + name + "' is listed twice in PAYMENT_ACCOUNTS");
            }
            PaymentAccount account = read(name, variables);
            if (account.live() != live) {
                // the classic mistake: live keys on a laptop, or test keys in production
                throw new IllegalStateException(PaymentAccount.secretKeyVariable(name) + " is a "
                        + (account.live() ? "LIVE" : "TEST") + " key, but payment.live (PAYMENT_LIVE) is " + live
                        + ": " + (live ? "production needs live keys" : "live keys only in production"));
            }
            accounts.put(name, account);
        }
        return new PaymentAccounts(accounts);
    }

    private static List<String> parseNames(String names) {
        if (names == null) {
            return List.of();
        }
        return Arrays.stream(names.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static PaymentAccount read(String name, Function<String, String> variables) {
        try {
            return new PaymentAccount(name,
                    variables.apply(PaymentAccount.secretKeyVariable(name)),
                    variables.apply(PaymentAccount.webhookSecretVariable(name)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // PaymentAccount's message already names the variable to fix, never its value
            throw new IllegalStateException("Payment account configuration: " + e.getMessage(), e);
        }
    }

    boolean isConfigured(String name) {
        return byName.containsKey(name);
    }

    Optional<PaymentAccount> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    // The account of an existing payment: the one it was made on, never the practitioner's current one
    // (D-057, guide 30, part 8). Always there: the startup check refuses a missing account of an open payment
    PaymentAccount of(Payment payment) {
        return byName(payment.getAccount()).orElseThrow(() -> new IllegalStateException(
                "Payment " + payment.getId() + " was made on account '" + payment.getAccount()
                        + "', which is no longer configured"));
    }

    // in the order of PAYMENT_ACCOUNTS
    List<String> names() {
        return List.copyOf(byName.sequencedKeySet());
    }
}
