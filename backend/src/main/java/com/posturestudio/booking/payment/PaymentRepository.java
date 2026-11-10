package com.posturestudio.booking.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    // the accounts that payments still to be captured or released were made on (guide 30, 4.3)
    @Query("select distinct p.account from Payment p where p.status in :statuses")
    List<String> accountsWithStatusIn(Collection<PaymentStatus> statuses);

    // at most one, by the partial unique index payment_one_pending_per_booking_uq (V10)
    Optional<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    Optional<Payment> findByStripeCheckoutSessionId(String checkoutSessionId);

    // Compare-and-set (guide 32, 5.2): the state of departure in the where. The first time 1 row, a repeat 0;
    // a concurrent repeat waits for the row lock, reads the condition again, and finds 0. Whoever gets 0
    // does nothing else. The version moves as JPA would move it
    @Modifying
    @Query("""
            update Payment p set p.status = :to, p.stripePaymentIntentId = :paymentIntentId, p.version = p.version + 1
            where p.id = :id and p.status = :from
            """)
    int authorize(Long id, String paymentIntentId, PaymentStatus from, PaymentStatus to);

    @Modifying
    @Query("update Payment p set p.status = :to, p.version = p.version + 1 where p.id = :id and p.status = :from")
    int move(Long id, PaymentStatus from, PaymentStatus to);
}
