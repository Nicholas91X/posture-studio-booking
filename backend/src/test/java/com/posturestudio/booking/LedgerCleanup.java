package com.posturestudio.booking;

import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ONLY IN TESTS (Nicholas, 2026-11-10). Deletes rows of the credit ledger, which the database otherwise refuses
 * to delete (V15, guide 36, 6.3). Needed by the tests that commit for real (two simultaneous transactions)
 * and must clean up after themselves: the container is shared with the other tests.
 * <p>
 * It switches the triggers off for one transaction, with {@code session_replication_role = replica}: a door
 * that only a superuser has, and the test user of Testcontainers is one. It is exactly the door step 14 closes
 * in production, where the application connects as a user that is not a superuser.
 * Careful: replica also switches off the foreign keys (in PostgreSQL they are triggers too), so the deletes
 * must still go in the right order, children first.
 */
public final class LedgerCleanup {

    private LedgerCleanup() {
    }

    public static void delete(PlatformTransactionManager transactionManager, JdbcTemplate jdbc,
                              Consumer<JdbcTemplate> deletes) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // "local": for this transaction only, the connection goes back to the pool as it was
            jdbc.execute("set local session_replication_role = replica");
            deletes.accept(jdbc);
        });
    }
}
