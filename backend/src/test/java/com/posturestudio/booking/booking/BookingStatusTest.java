package com.posturestudio.booking.booking;

import static com.posturestudio.booking.booking.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

// The state machine, plain Java. The expected arrows are written by hand from the diagram of guide 18, 1.1,
// not computed: every one of the 49 pairs is checked, so a forgotten or an extra arrow fails here
public class BookingStatusTest {

    private static final Map<BookingStatus, Set<BookingStatus>> DIAGRAM = Map.of(
            PENDING_PAYMENT, Set.of(PENDING_APPROVAL, EXPIRED),
            PENDING_APPROVAL, Set.of(CONFIRMED, REJECTED, APPROVAL_EXPIRED, WITHDRAWN),
            CONFIRMED, Set.of(),
            EXPIRED, Set.of(),
            REJECTED, Set.of(),
            APPROVAL_EXPIRED, Set.of(),
            WITHDRAWN, Set.of());

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
        assertThat(allowed).isEqualTo(6);
    }

    @Test
    void theDiagramCoversEveryState() {
        // if a state is added to the enum, this test reminds to draw its arrows here too
        assertThat(DIAGRAM.keySet()).containsExactlyInAnyOrder(BookingStatus.values());
    }

    @Test
    void noStateMovesToItself() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.canMoveTo(status)).as(status.name()).isFalse();
        }
    }
}
