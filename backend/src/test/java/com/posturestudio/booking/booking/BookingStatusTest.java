package com.posturestudio.booking.booking;

import static com.posturestudio.booking.booking.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The state machine, plain Java. The expected arrows are written by hand from the diagrams of guide 18, 1.1 and
// guide 42, part 2, not computed: every one of the 144 pairs is checked, so a forgotten or an extra arrow fails
// here. The arrows of step 7 are the same as before step 11: adding states did not change them
public class BookingStatusTest {

    private static final Map<BookingStatus, Set<BookingStatus>> DIAGRAM = Map.ofEntries(
            Map.entry(PENDING_PAYMENT, Set.of(PENDING_APPROVAL, EXPIRED)),
            Map.entry(PENDING_APPROVAL, Set.of(CONFIRMED, REJECTED, APPROVAL_EXPIRED, WITHDRAWN)),
            Map.entry(CONFIRMED, Set.of(CANCELLED_IN_TIME, CANCELLED_LATE, CANCELLED_BY_STUDIO, COMPLETED, NO_SHOW)),
            Map.entry(EXPIRED, Set.of()),
            Map.entry(REJECTED, Set.of()),
            Map.entry(APPROVAL_EXPIRED, Set.of()),
            Map.entry(WITHDRAWN, Set.of()),
            Map.entry(CANCELLED_IN_TIME, Set.of()),
            Map.entry(CANCELLED_LATE, Set.of()),
            Map.entry(CANCELLED_BY_STUDIO, Set.of()),
            Map.entry(COMPLETED, Set.of(NO_SHOW)),
            Map.entry(NO_SHOW, Set.of(COMPLETED)));

    @Test
    void theMachineAllowsExactlyTheArrowsOfTheDiagram() {
        List<String> wrong = new ArrayList<>();
        int allowed = 0;
        for (BookingStatus from : BookingStatus.values()) {
            for (BookingStatus to : BookingStatus.values()) {
                boolean expected = DIAGRAM.get(from).contains(to);
                if (from.canMoveTo(to) != expected) {
                    wrong.add(from + " -> " + to + " should be " + (expected ? "allowed" : "refused"));
                }
                if (expected) {
                    allowed++;
                }
            }
        }

        // all the mistakes at once, not only the first one
        assertThat(wrong).isEmpty();
        assertThat(allowed).isEqualTo(13);
    }

    @Test
    void theDiagramCoversEveryState() {
        // if a state is added to the enum, this test reminds to draw its arrows here too
        assertThat(DIAGRAM.keySet()).containsExactlyInAnyOrder(BookingStatus.values());
    }

    // a pending request is never "cancelled by the studio": that is a rejection (guide 42, part 4)
    @Test
    void onlyAConfirmedSessionCanBeCancelledByTheStudio() {
        for (BookingStatus from : BookingStatus.values()) {
            assertThat(from.canMoveTo(CANCELLED_BY_STUDIO)).as(from.name()).isEqualTo(from == CONFIRMED);
        }
    }

    // no state of step 11 holds the time: the exclusion constraint of V4 did not change (guide 42, part 2)
    @Test
    void theStatesThatHoldTheTimeAreStillTheThreeOfStep7() {
        assertThat(HOLDS_TIME).containsExactlyInAnyOrder(PENDING_PAYMENT, PENDING_APPROVAL, CONFIRMED);
    }

    @Test
    void noStateMovesToItself() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.canMoveTo(status)).as(status.name()).isFalse();
        }
    }
}
