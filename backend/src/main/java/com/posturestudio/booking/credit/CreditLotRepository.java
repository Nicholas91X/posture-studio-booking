package com.posturestudio.booking.credit;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// A Repository, not a JpaRepository: a lot is never deleted (credit_lot_no_delete_tg), so there is no delete
// to offer. Pieces 3 to 5 add the reads they need (the lot that ends first, with its lock)
interface CreditLotRepository extends Repository<CreditLot, Long> {

    CreditLot save(CreditLot lot);

    Optional<CreditLot> findById(long id);

    // the lots bought by these payments, with the credits of their LOT_CREATED movement: one query for a list
    @Query("""
            select new com.posturestudio.booking.credit.PackageLot(l.paymentId, l.id, m.amount, l.validUntil)
            from CreditLot l join CreditMovement m on m.creditLotId = l.id
            where l.paymentId in :paymentIds
              and m.reason = com.posturestudio.booking.credit.MovementReason.LOT_CREATED
            """)
    List<PackageLot> findPackageLotsOf(Collection<Long> paymentIds);
}
