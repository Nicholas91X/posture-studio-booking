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
}
