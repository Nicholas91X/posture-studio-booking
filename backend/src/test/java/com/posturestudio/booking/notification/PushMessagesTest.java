package com.posturestudio.booking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.posturestudio.booking.booking.BookingEvent.ApprovalExpired;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.PaidWith;
import com.posturestudio.booking.credit.CreditEvent.PackagePurchased;
import com.posturestudio.booking.notification.PushMessage.PushPayload;

// Which events have a push, and its text (guide 28, parts 8 and 10): a pure function, no Spring.
// Monday 9 November 2026, 9:00 in Rome; the answer is due by Friday 6 November, 22:00
class PushMessagesTest {

    private static final Instant MONDAY_9 = Instant.parse("2026-11-09T08:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-06T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private final PushMessages pushes = new PushMessages("https://studio.example");

    // a package sold (guide 37, part 10): how many and until when, never who nor which treatment; for a day
    @Test
    void aSoldPackageHasAPushWithoutWhoNorWhat() {
        PushMessage push = pushes.pushFor(new PackagePurchased(7, 2, 3, 4, 5, java.time.LocalDate.of(2027, 2, 8)))
                .orElseThrow();

        assertThat(push.payload()).isEqualTo(new PushPayload("Pacchetto venduto",
                "5 sedute · valide fino a lunedì 8 febbraio 2027", "https://studio.example", "lot-7"));
        assertThat(push.timeToLive()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aRequestToApproveHasAPushWithDateTimeAndDeadline() {
        PushMessage push = pushes.pushFor(new ApprovalRequested(42, 2, 3, 4, MONDAY_9, DEADLINE, PaidWith.CARD), NOW).orElseThrow();

        assertThat(push.payload()).isEqualTo(new PushPayload("Nuova prenotazione da approvare",
                "lunedì 9 novembre, 9:00 · rispondi entro venerdì 6 novembre, 22:00",
                "https://studio.example", "booking-42"));
    }

    // the push service keeps it until the deadline, no longer: after it the request is closed
    @Test
    void itLivesUntilTheDeadline() {
        assertThat(pushes.pushFor(new ApprovalRequested(42, 2, 3, 4, MONDAY_9, DEADLINE, PaidWith.CARD), NOW).orElseThrow().timeToLive())
                .isEqualTo(Duration.ofHours(12));
        assertThat(pushes.pushFor(new ApprovalRequested(42, 2, 3, 4, MONDAY_9, DEADLINE, PaidWith.CARD), DEADLINE.plusSeconds(1))
                .orElseThrow().timeToLive()).isZero();
    }

    // nothing for the practitioner to do in the others (guide 28, 8.1)
    @Test
    void theOtherEventsHaveNoPush() {
        assertThat(pushes.pushFor(new BookingConfirmed(42, 2, 3, 4, MONDAY_9, PaidWith.CARD), NOW)).isEmpty();
        assertThat(pushes.pushFor(new BookingRejected(42, 2, 3, 4, MONDAY_9, PaidWith.CARD), NOW)).isEmpty();
        assertThat(pushes.pushFor(new ApprovalExpired(42, 2, 3, 4, MONDAY_9, PaidWith.CARD), NOW)).isEmpty();
        assertThat(pushes.pushFor(new BookingWithdrawn(42, 2, 3, 4, MONDAY_9, PaidWith.CARD), NOW)).isEmpty();
    }
}
