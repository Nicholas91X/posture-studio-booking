package com.posturestudio.booking.payment;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The Stripe events already processed (V12, guide 32, 5.1). Plain SQL: one insert that says at the same time
 * "record it" and "was it new?", which JPA cannot say in one statement.
 */
@Repository
class SeenStripeEvents {

    private final JdbcTemplate jdbc;

    SeenStripeEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // true: first time, process it. false: already processed, change nothing.
    // Must run in the transaction of the processing (guide 32, 5.1). A second delivery arriving at the same
    // time waits on the primary key until the first commits, then finds the row: false
    boolean firstTime(String eventId, String account, String type, Instant now) {
        return jdbc.update("""
                insert into stripe_event (event_id, account, type, received_at) values (?, ?, ?, ?)
                on conflict (event_id) do nothing
                """, eventId, account, type, Timestamp.from(now)) == 1;
    }
}
