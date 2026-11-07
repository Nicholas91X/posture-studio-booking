package com.posturestudio.booking.booking;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two jobs of guide 21, every minute after the end of the previous run (fixedDelay: they never
 * chase each other). No logic here: BookingService decides, reading the injected Clock.
 *
 * A class of its own on purpose: expireApproval is called through the proxy of BookingService, so each
 * request gets its own transaction. Called as this.expireApproval(...) from inside BookingService, the
 * proxy would be skipped and @Transactional ignored (guide 21, 3.2).
 * Tests call these methods directly, with a clock they move: no real minute is ever waited.
 */
@Component
class BookingJobs {

    private static final Logger log = LoggerFactory.getLogger(BookingJobs.class);

    private final BookingService bookings;

    BookingJobs(BookingService bookings) {
        this.bookings = bookings;
    }

    // PENDING_PAYMENT -> EXPIRED (D-008)
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void expireHolds() {
        int expired = bookings.expireHolds();
        if (expired > 0) {
            log.info("Expired {} payment holds", expired);
        }
    }

    // PENDING_APPROVAL -> APPROVAL_EXPIRED, hold on the card released (D-030, D-031).
    // One transaction per request: one failure does not stop the others
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void expireApprovals() {
        List<Long> ids = bookings.expiredApprovals();
        for (long id : ids) {
            try {
                bookings.expireApproval(id);
            } catch (RuntimeException e) {
                // the next run tries again: the request is still PENDING_APPROVAL
                log.error("Could not expire the approval of booking {}", id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("Looked at {} requests past their deadline", ids.size());
        }
    }
}
