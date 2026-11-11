package com.posturestudio.booking.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    // the accounts that payments still to be captured or released were made on (guide 30, 4.3)
    @Query("select distinct p.account from Payment p where p.status in :statuses")
    List<String> accountsWithStatusIn(Collection<PaymentStatus> statuses);

    // at most one, by the partial unique index payment_one_pending_per_booking_uq (V10)
    Optional<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    // WITH the lock (select ... for update): whoever changes a payment, or calls Stripe about it, holds its row
    // (guide 33, parts 8 and 9). A second transaction waits here, then reads what the first saved
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedByStripeCheckoutSessionId(String checkoutSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedByStripePaymentIntentId(String paymentIntentId);

    // at most one AUTHORIZED per booking: every other payment of the booking was released (D-059, D-062)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findLockedById(Long id);

    // the packages of a customer, the latest first (guide 37, part 5)
    List<Payment> findByCustomerIdAndPurposeOrderByIdDesc(Long customerId, PaymentPurpose purpose);

    // one purchase of a customer: someone else's is not found (404, as every "of another")
    Optional<Payment> findByIdAndCustomerIdAndPurpose(Long id, Long customerId, PaymentPurpose purpose);

    // the held payments that owe a release whose time has come (D-064), the oldest first
    @Query("select p.id from Payment p where p.status = :status and p.nextReleaseAt <= :now order by p.nextReleaseAt")
    List<Long> idsOfReleasesDue(PaymentStatus status, Instant now);
}
