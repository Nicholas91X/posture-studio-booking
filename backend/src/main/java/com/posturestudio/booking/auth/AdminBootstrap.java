package com.posturestudio.booking.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first admin (D-021, guide 26, part 2): only an admin can create special accounts, so the first one
 * comes from the configuration. ADMIN_EMAIL is not a secret: signing in needs the code sent to that
 * mailbox (D-014). It is personal data, though, so it stays in .env and never in the repository.
 *
 * It only CREATES, never changes an account: promoting the account of a typo in .env would hand the
 * platform to whoever owns that email. In doubt it stops the startup and says why (fail fast).
 *
 * An ApplicationRunner runs once the context is ready, so after Flyway; an exception here stops the start.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    enum Outcome { NOT_CONFIGURED, CREATED, ALREADY_THERE, DEACTIVATED }

    private final AppUserRepository users;
    private final TransactionTemplate transaction;
    private final String adminEmail;

    AdminBootstrap(AppUserRepository users, PlatformTransactionManager transactionManager,
                   @Value("${auth.admin-email:}") String adminEmail) {
        this.users = users;
        // a TransactionTemplate, not @Transactional: run() calling a method of this same class would not go
        // through the proxy, and the annotation would do nothing (guide 26, 2.6)
        this.transaction = new TransactionTemplate(transactionManager);
        this.adminEmail = adminEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        // the email is not logged: personal data has no place in the logs.
        // A switch on the enum with arrows and no default: a new Outcome would not compile until handled
        switch (ensureAdmin()) {
            case NOT_CONFIGURED -> log.info("ADMIN_EMAIL not set: no admin account checked at startup");
            case CREATED -> log.info("Admin account created from ADMIN_EMAIL");
            case ALREADY_THERE -> log.debug("Admin account of ADMIN_EMAIL already there");
            case DEACTIVATED -> log.warn("The admin account of ADMIN_EMAIL is deactivated: left as it is");
        }
    }

    Outcome ensureAdmin() {
        if (adminEmail.isBlank()) {
            return Outcome.NOT_CONFIGURED;
        }
        Email email = parse(adminEmail);
        return transaction.execute(status -> {
            boolean created = users.insertAdminIfAbsent(email.value()) == 1;
            // read again in every case: inserted now, by another instance a moment ago, or there for months
            AppUser user = users.findByEmail(email).orElseThrow();
            if (user.getRole() != Role.ADMIN) {
                throw new IllegalStateException("ADMIN_EMAIL belongs to a " + user.getRole()
                        + " account: one role per user (D-021), and the startup never promotes an account."
                        + " Use another email, or change the role by hand if that is really meant");
            }
            if (created) {
                return Outcome.CREATED;
            }
            // never reactivated here: only an explicit act reactivates (D-050)
            return user.isActive() ? Outcome.ALREADY_THERE : Outcome.DEACTIVATED;
        });
    }

    private static Email parse(String value) {
        try {
            return new Email(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ADMIN_EMAIL is not a valid email address", e);
        }
    }
}
