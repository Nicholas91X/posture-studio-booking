package com.posturestudio.booking.credit;

import java.util.List;

import org.springframework.data.repository.Repository;

// append-only, as the ledger: no delete to offer
interface CreditLotExtensionRepository extends Repository<CreditLotExtension, Long> {

    CreditLotExtension save(CreditLotExtension extension);

    List<CreditLotExtension> findByCreditLotIdOrderById(long lotId);
}
