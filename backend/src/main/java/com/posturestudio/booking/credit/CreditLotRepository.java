package com.posturestudio.booking.credit;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;

// A Repository, not a JpaRepository: a lot is never deleted (credit_lot_no_delete_tg), so there is no delete
// to offer. Pieces 3 to 5 add the reads they need (the lot that ends first, with its lock)
interface CreditLotRepository extends Repository<CreditLot, Long> {

    CreditLot save(CreditLot lot);

    Optional<CreditLot> findById(long id);

    // "First expired, first out" (guide 38, part 5): the lots of this customer, practitioner and service that
    // cover this day and still have credits, the one that ends first first; the same day, the oldest first.
    // having filters AFTER the sum, which a where cannot do. Served by credit_lot_customer_usable_ix (V15)
    @Query("""
            select l.id
            from CreditLot l join CreditMovement m on m.creditLotId = l.id
            where l.customerId = :customerId and l.practitionerId = :practitionerId and l.serviceId = :serviceId
              and l.validUntil >= :day
            group by l.id, l.validUntil
            having sum(m.amount) > 0
            order by l.validUntil, l.id
            """)
    List<Long> findUsableIds(long customerId, long practitionerId, long serviceId, LocalDate day);

    // WITH the lock on the row (select ... for update): every debit of a lot waits here (D-069)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CreditLot> findLockedById(long id);

    // the lots bought by these payments, with the credits of their LOT_CREATED movement: one query for a list
    @Query("""
            select new com.posturestudio.booking.credit.PackageLot(l.paymentId, l.id, m.amount, l.validUntil)
            from CreditLot l join CreditMovement m on m.creditLotId = l.id
            where l.paymentId in :paymentIds
              and m.reason = com.posturestudio.booking.credit.MovementReason.LOT_CREATED
            """)
    List<PackageLot> findPackageLotsOf(Collection<Long> paymentIds);
}
