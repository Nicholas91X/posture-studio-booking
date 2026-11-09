package com.posturestudio.booking.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every hour, deletes the access codes older than 24 hours (guide 24, 6.5). Safe with two instances:
 * a second delete finds nothing to delete. Switched on and off with the other jobs (SchedulingConfiguration).
 */
@Component
class LoginCodeJobs {

    private static final Logger log = LoggerFactory.getLogger(LoginCodeJobs.class);

    private final LoginCodeService codes;

    LoginCodeJobs(LoginCodeService codes) {
        this.codes = codes;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void purgeOld() {
        int deleted = codes.purgeOld();
        if (deleted > 0) {
            log.info("Deleted {} old access codes", deleted);
        }
    }
}
