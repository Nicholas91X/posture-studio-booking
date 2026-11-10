package com.posturestudio.booking.payment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The job of D-064: every minute, the releases that failed and whose time has come are tried again. Switched on
 * and off with the other jobs (booking.jobs.enabled, SchedulingConfiguration). A class of its own, as BookingJobs:
 * each payment goes through the proxy of PaymentReleases, in a transaction of its own (guide 21, 3.2).
 * Tests call it directly, with a clock they move.
 */
@Component
class PaymentJobs {

    private static final Logger log = LoggerFactory.getLogger(PaymentJobs.class);

    private final PaymentReleases releases;

    PaymentJobs(PaymentReleases releases) {
        this.releases = releases;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void retryReleases() {
        List<Long> due = releases.due();
        for (long id : due) {
            try {
                releases.retry(id);
            } catch (RuntimeException e) {
                // the next run tries again: the payment still owes its release
                log.error("Could not retry the release of payment {}", id, e);
            }
        }
    }
}
