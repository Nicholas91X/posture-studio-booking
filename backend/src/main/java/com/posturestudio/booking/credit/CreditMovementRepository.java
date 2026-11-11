package com.posturestudio.booking.credit;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// A Repository, not a JpaRepository: the ledger only grows, so there is no delete to offer (guide 36, part 6)
interface CreditMovementRepository extends Repository<CreditMovement, Long> {

    CreditMovement save(CreditMovement movement);

    // the balance of a lot is the sum of its movements (D-009)
    @Query("select coalesce(sum(m.amount), 0) from CreditMovement m where m.creditLotId = :lotId")
    long balanceOf(long lotId);

    List<CreditMovement> findByCreditLotIdOrderById(long lotId);

    // the credit a booking took: at most one (credit_movement_one_debit_per_booking_uq)
    java.util.Optional<CreditMovement> findByBookingIdAndReason(long bookingId, MovementReason reason);

    // whether a booking already gave a credit back: at most once (credit_movement_one_return_per_booking_uq)
    boolean existsByBookingIdAndAmountGreaterThan(long bookingId, int amount);
}
