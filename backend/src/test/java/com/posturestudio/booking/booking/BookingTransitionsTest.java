package com.posturestudio.booking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.posturestudio.booking.FixedClockConfiguration;
import com.posturestudio.booking.TestcontainersConfiguration;
import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.booking.BookingEvent.ApprovalRequested;
import com.posturestudio.booking.booking.BookingEvent.BookingConfirmed;
import com.posturestudio.booking.booking.BookingEvent.BookingRejected;
import com.posturestudio.booking.booking.BookingEvent.BookingWithdrawn;
import com.posturestudio.booking.booking.BookingEvent.LatePaymentReleased;
import com.posturestudio.booking.booking.BookingTestDoubles.RecordingCardHolds;
import com.posturestudio.booking.catalog.CatalogService;
import com.posturestudio.booking.customer.CustomerService;
import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.schedule.ScheduleService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

// The use cases of the state machine (guide 20, 8.2), with the database, the card double and the events
// published during the test (@RecordApplicationEvents: what was published, not what a listener got after
// commit, which is BookingAfterCommitTest). Today is Friday 6 November 2026, 10:00 in Rome.
// Bookings are requested through the service: their request time comes from the fixed clock
@DataJpaTest
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class, BookingService.class, com.posturestudio.booking.credit.CreditService.class,
        BookingOccupiedTime.class, ScheduleService.class, CatalogService.class, PractitionerService.class,
        AccountService.class, CustomerService.class, BookingTestDoubles.class})
@RecordApplicationEvents
public class BookingTransitionsTest {

    private static final String MONDAY_9 = "2026-11-09T09:00+01:00";

    @Autowired
    BookingService service;

    @Autowired
    RecordingCardHolds cards;

    @Autowired
    ApplicationEvents events;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    private long anna;
    private long marco;
    private long seduta;
    private long cliente;
    private long altroCliente;

    @BeforeEach
    void annaWorksMondayToFriday() {
        BookingTestData data = new BookingTestData(jdbc);
        anna = data.practitioner("anna@example.com");
        marco = data.practitioner("marco@example.com");
        seduta = data.service(anna, 60);
        cliente = data.customer("cliente@example.com");
        altroCliente = data.customer("altro@example.com");
        data.weekdays(anna, "2026-11-02");
        cards.reset();
    }

    // ***** payment *****

    @Test
    void aPaymentInManualModeWaitsForThePractitioner() {
        long id = requested();

        PaymentOutcome outcome = service.paymentAuthorized(id);

        assertThat(outcome).isEqualTo(new PaymentOutcome.Accepted(BookingStatus.PENDING_APPROVAL));
        CustomerBookingView paid = service.findForCustomer(cliente, id);
        // requested at 10:00, default window of 12 hours
        assertThat(paid.approvalDeadline()).isEqualTo(OffsetDateTime.parse("2026-11-06T22:00+01:00"));
        assertThat(cards.calls).isEmpty();
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(ApprovalRequested.class);
    }

    @Test
    void aPaymentInAutomaticModeConfirmsAtOnceThroughTheSamePath() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        long id = requested();

        PaymentOutcome outcome = service.paymentAuthorized(id);

        assertThat(outcome).isEqualTo(new PaymentOutcome.Accepted(BookingStatus.CONFIRMED));
        assertThat(cards.calls).containsExactly("capture " + id + " 3000");
        // no request reaches the practitioner: only the confirmation
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(BookingConfirmed.class);
        persisted();
        assertThat(row(id)).containsEntry("status", "CONFIRMED");
    }

    // D-059, guide 32, part 7: a second payment is not an error (for Stripe an error means "try again"),
    // it is a payment the booking does not want: the webhook releases it
    // D-063, checked again after piece 4: AUTOMATIC with a capture refused for good. The request keeps its deadline
    // and goes to the practitioner as in manual mode (ApprovalRequested: push or email)
    @Test
    void aRefusedCaptureInAutomaticModeLeavesTheRequestToThePractitionerWithItsDeadline() {
        jdbc.update("update practitioner set booking_approval = 'AUTOMATIC' where id = ?", anna);
        cards.declineCaptures = true;
        long id = requested();

        PaymentOutcome outcome = service.paymentAuthorized(id);

        assertThat(outcome).isEqualTo(new PaymentOutcome.Accepted(BookingStatus.PENDING_APPROVAL));
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(ApprovalRequested.class);
        assertThat(service.findForCustomer(cliente, id).approvalDeadline())
                .isEqualTo(OffsetDateTime.parse("2026-11-06T22:00+01:00"));
    }

    @Test
    void aSecondPaymentOfTheSameBookingIsNotWanted() {
        long id = paid();
        cards.reset();

        PaymentOutcome outcome = service.paymentAuthorized(id);

        assertThat(outcome).isEqualTo(new PaymentOutcome.NotWanted(BookingStatus.PENDING_APPROVAL));
        persisted();
        assertThat(row(id)).containsEntry("status", "PENDING_APPROVAL");
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void aPaymentForAnExpiredBookingReleasesTheHoldAndChangesNothing() {
        long id = requested();
        persisted();
        jdbc.update("update booking set status = 'EXPIRED' where id = ?", id);

        PaymentOutcome outcome = service.paymentAuthorized(id);

        // D-031: no credit, no error. The booking does not release the money any more: it answers, and the
        // webhook releases THAT payment (guide 32, part 7). The customer is told by email (part 9)
        assertThat(outcome).isEqualTo(new PaymentOutcome.NotWanted(BookingStatus.EXPIRED));
        assertThat(cards.calls).isEmpty();
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(LatePaymentReleased.class);
    }

    @Test
    void aPaymentForAnUnknownBookingIsNotFound() {
        Assertions.assertThrows(NotFoundException.class, () -> service.paymentAuthorized(999999));
    }

    // ***** the practitioner answers *****

    @Test
    void anApprovalCapturesAndConfirms() {
        long id = paid();

        PanelBookingView approved = service.approve(anna, id);

        assertThat(approved.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(cards.calls).containsExactly("capture " + id + " 3000");
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(BookingConfirmed.class);
        persisted();
        assertThat(row(id)).containsEntry("status", "CONFIRMED").containsKey("decided_at");
        assertThat(row(id).get("decided_at")).isNotNull();
    }

    @Test
    void aRejectionReleasesTheHoldAndFreesTheSlot() {
        long id = paid();

        PanelBookingView rejected = service.reject(anna, id);

        assertThat(rejected.status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(cards.calls).containsExactly("cancel " + id);
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(BookingRejected.class);
        // the slot can be booked again: the rejected booking left the exclusion constraint
        persisted();
        assertThat(request(altroCliente).status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    @Test
    void anotherPractitionerCannotAnswer() {
        long id = paid();

        Assertions.assertThrows(NotFoundException.class, () -> service.approve(marco, id));
        Assertions.assertThrows(NotFoundException.class, () -> service.reject(marco, id));
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void anAnswerAfterTheDeadlineIsRefusedAndNothingIsCaptured() {
        long id = paid();
        persisted();
        // the deadline passed half an hour ago, and the job of piece 4 has not closed the request yet
        jdbc.update("update booking set approval_deadline = ? where id = ?", OffsetDateTime.parse("2026-11-06T09:30+01:00"), id);

        assertRejected(ErrorCode.APPROVAL_DEADLINE_PASSED, () -> service.approve(anna, id));
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void anUnpaidBookingCannotBeApproved() {
        long id = requested();

        assertRejected(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED, () -> service.approve(anna, id));
        assertThat(cards.calls).isEmpty();
    }

    // ***** the customer withdraws *****

    @Test
    void aWithdrawalReleasesTheHold() {
        long id = paid();

        CustomerBookingView withdrawn = service.withdraw(cliente, id);

        assertThat(withdrawn.status()).isEqualTo(BookingStatus.WITHDRAWN);
        assertThat(cards.calls).containsExactly("cancel " + id);
        assertThat(events.stream(BookingEvent.class)).singleElement().isInstanceOf(BookingWithdrawn.class);
        persisted();
        assertThat(row(id).get("cancelled_at")).isNotNull();
    }

    @Test
    void anotherCustomerCannotWithdraw() {
        long id = paid();

        Assertions.assertThrows(NotFoundException.class, () -> service.withdraw(altroCliente, id));
        assertThat(cards.calls).isEmpty();
    }

    @Test
    void aConfirmedBookingCannotBeWithdrawn() {
        // cancelling a confirmed booking is step 11, with its own rules (notice, credits)
        long id = paid();
        service.approve(anna, id);

        assertRejected(ErrorCode.BOOKING_TRANSITION_NOT_ALLOWED, () -> service.withdraw(cliente, id));
    }

    // ***** helpers *****

    private CustomerBookingView request(long customerId) {
        return service.request(customerId, new NewBooking(anna, seduta, OffsetDateTime.parse(MONDAY_9)));
    }

    private long requested() {
        return request(cliente).id();
    }

    // requested and paid in MANUAL mode; the events of the arrangement are forgotten
    private long paid() {
        long id = requested();
        service.paymentAuthorized(id);
        events.clear();
        return id;
    }

    // what the commit would do: write the changes, then forget the entities, so that SQL changes made by
    // the test and the next read of the service both see the database, not the objects in memory
    private void persisted() {
        entityManager.flush();
        entityManager.clear();
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select status, decided_at, cancelled_at from booking where id = ?", id);
    }

    private static void assertRejected(ErrorCode code, Runnable call) {
        var exception = Assertions.assertThrows(BusinessRuleException.class, call::run);
        assertThat(exception.getCode()).isEqualTo(code);
    }
}
