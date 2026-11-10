package com.posturestudio.booking.credit;

import java.util.Optional;

import org.springframework.data.repository.Repository;

// A Repository, not a JpaRepository: a lot is never deleted (credit_lot_no_delete_tg), so there is no delete
// to offer. Pieces 2 to 5 add the reads they need (the lot that ends first, with its lock)
interface CreditLotRepository extends Repository<CreditLot, Long> {

    CreditLot save(CreditLot lot);

    Optional<CreditLot> findById(long id);
}
