package com.posturestudio.booking.payment;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * At startup, after Flyway (guide 30, 4.3): every account the database still needs must be configured.
 * - an account assigned to a practitioner: his next payments go there;
 * - an account of an open payment (PENDING, AUTHORIZED): it must still be captured or released ON THAT
 *   account (D-057). Closed payments do not count: nothing is done on them any more.
 * Otherwise the startup stops and says which name is missing, before a customer pays the consequences:
 * e.g. an account removed from .env while sessions were still held on it.
 *
 * An ApplicationRunner as AdminBootstrap; an exception here stops the application.
 */
@Component
class PaymentStartupCheck implements ApplicationRunner {

    private final PaymentAccounts accounts;
    private final PaymentAccountAssignmentRepository assignments;
    private final PaymentRepository payments;

    PaymentStartupCheck(PaymentAccounts accounts, PaymentAccountAssignmentRepository assignments,
                        PaymentRepository payments) {
        this.accounts = accounts;
        this.assignments = assignments;
        this.payments = payments;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    // No @Transactional: run() calls check() on this same object, so the proxy would be skipped and the
    // annotation would do nothing (guide 26, 2.6). Not needed either: two reads, each in the repository's own
    // read-only transaction
    void check() {
        SortedSet<String> missing = new TreeSet<>();
        List<String> assigned = assignments.assignedAccounts();
        List<String> open = payments.accountsWithStatusIn(PaymentStatus.OPEN);
        assigned.stream().filter(name -> !accounts.isConfigured(name)).forEach(missing::add);
        open.stream().filter(name -> !accounts.isConfigured(name)).forEach(missing::add);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Payment accounts still used in the database but not configured: "
                    + String.join(", ", missing) + ". Add them back to PAYMENT_ACCOUNTS, with their keys"
                    + " (STRIPE_<NAME>_SECRET_KEY, STRIPE_<NAME>_WEBHOOK_SECRET), or reassign the practitioners"
                    + " and wait for their open payments to close (D-057)");
        }
    }
}
