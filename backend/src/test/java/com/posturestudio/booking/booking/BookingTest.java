package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.TimeRange;

// The transitions of the entity, plain Java: no Spring, no database.
// Requested on Friday 6 November 2026 at 10:00 in Rome (09:00 UTC) for Monday 9:00-10:00
public class BookingTest {

    private static final Instant REQUESTED = Instant.parse("2026-11-06T09:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-06T11:00:00Z");
    private static final TimeRange MONDAY_9 = new TimeRange(
            Instant.parse("2026-11-09T08:00:00Z"), Instant.parse("2026-11-09T09:00:00Z"));

    @Test
    void aRequestIsPendingPaymentHeldFor32Minutes() {
        Booking booking = requested();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        // 32 and not 30 (D-061): the Stripe session must last at least 30 minutes from its own creation
        assertThat(booking.getHoldExpiresAt()).isEqualTo(Instant.parse("2026-11-06T09:32:00Z"));
        assertThat(booking.getCreatedAt()).isEqualTo(REQUESTED);
    }

    // the notice photographed at birth (guide 42, part 6): until 24 hours before Monday 9:00 in Rome
    @Test
    void everyBirthPhotographsTheNotice() {
        Instant sundayNine = Instant.parse("2026-11-08T08:00:00Z");

        assertThat(requested().getFreeCancellationUntil()).isEqualTo(sundayNine);
        assertThat(Booking.requestedWithCredit(1L, 2L, 3L, MONDAY_9, 3000, REQUESTED, Duration.ofHours(12),
                Duration.ofHours(24)).getFreeCancellationUntil()).isEqualTo(sundayNine);
        assertThat(Booking.bookedFromPanel(1L, 2L, 3L, MONDAY_9, 3000, REQUESTED, PaidWith.IN_STUDIO,
                Duration.ofHours(48)).getFreeCancellationUntil()).isEqualTo(Instant.parse("2026-11-07T08:00:00Z"));
    }

    @Test
    void aNoticeOfZeroIsAProgrammingError() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> Booking.requestedByCustomer(1L, 2L, 3L, MONDAY_9, 3000, REQUESTED, Duration.ZERO));
    }

    @Test
    void thePaymentStartsTheWaitWithADeadlineCountedFromTheRequest() {
        Booking booking = requested();

        booking.paymentAuthorized(Duration.ofHours(12));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_APPROVAL);
        // from the request, not from the payment (guide 20, 6.1)
        assertThat(booking.getApprovalDeadline()).isEqualTo(Instant.parse("2026-11-06T21:00:00Z"));
    }

    @Test
    void anApprovalConfirmsAndRecordsWhen() {
        Booking booking = waitingForApproval();

        booking.approve(LATER);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getDecidedAt()).isEqualTo(LATER);
    }

    @Test
    void aRejectionRecordsWhen() {
        Booking booking = waitingForApproval();

        booking.reject(LATER);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.REJECTED);
        assertThat(booking.getDecidedAt()).isEqualTo(LATER);
    }

    @Test
    void aWithdrawalRecordsWhenInItsOwnColumn() {
        Booking booking = waitingForApproval();

        booking.withdraw(LATER);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.WITHDRAWN);
        assertThat(booking.getCancelledAt()).isEqualTo(LATER);
        assertThat(booking.getDecidedAt()).isNull();
    }

    @Test
    void approvingBeforeThePaymentIsRefused() {
        Booking booking = requested();

        var exception = Assertions.assertThrows(BusinessRuleException.class, () -> booking.approve(LATER));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.getDecidedAt()).isNull();
    }

    @Test
    void aWithdrawnRequestCannotBeApproved() {
        // the race of guide 20, 2.6: the customer withdrew a moment before the practitioner's answer
        Booking booking = waitingForApproval();
        booking.withdraw(LATER);

        var exception = Assertions.assertThrows(BusinessRuleException.class, () -> booking.approve(LATER));

        assertThat(exception.getCode()).isEqualTo(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.WITHDRAWN);
    }

    @Test
    void aSecondPaymentIsRefused() {
        Booking booking = waitingForApproval();

        Assertions.assertThrows(BusinessRuleException.class, () -> booking.paymentAuthorized(Duration.ofHours(12)));
        assertThat(booking.getApprovalDeadline()).isEqualTo(Instant.parse("2026-11-06T21:00:00Z"));
    }

    private static Booking requested() {
        return Booking.requestedByCustomer(1L, 2L, 3L, MONDAY_9, 3000, REQUESTED, Duration.ofHours(24));
    }

    private static Booking waitingForApproval() {
        Booking booking = requested();
        booking.paymentAuthorized(Duration.ofHours(12));
        return booking;
    }
}
