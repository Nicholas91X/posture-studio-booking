package com.posturestudio.booking.booking;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.TransactionalEventListener;

// Test doubles for the @DataJpaTest tests of booking, imported by all of them so they share one context.
// The beans live as long as the context: tests that look at them reset them first
@TestConfiguration(proxyBeanMethods = false)
public class BookingTestDoubles {

    @Bean
    RecordingCardHolds recordingCardHolds() {
        return new RecordingCardHolds();
    }

    @Bean
    CommittedEvents committedEvents() {
        return new CommittedEvents();
    }

    // CardHolds that remembers what it was asked, and can refuse a capture like a declined card
    static class RecordingCardHolds implements CardHolds {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile boolean declineCaptures;

        @Override
        public void capture(long bookingId, long amountCents) {
            if (declineCaptures) {
                throw new IllegalStateException("card declined");
            }
            calls.add("capture " + bookingId + " " + amountCents);
        }

        @Override
        public void cancel(long bookingId) {
            calls.add("cancel " + bookingId);
        }

        void reset() {
            calls.clear();
            declineCaptures = false;
        }
    }

    // A listener as step 8 will write it: @TransactionalEventListener delivers only after a successful
    // commit. In a @DataJpaTest test the transaction is rolled back, so this never receives anything there
    static class CommittedEvents {

        final List<BookingEvent> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(BookingEvent event) {
            received.add(event);
        }
    }
}
