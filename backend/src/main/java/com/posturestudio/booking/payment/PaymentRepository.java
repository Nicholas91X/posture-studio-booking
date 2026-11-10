package com.posturestudio.booking.payment;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    // the accounts that payments still to be captured or released were made on (guide 30, 4.3)
    @Query("select distinct p.account from Payment p where p.status in :statuses")
    List<String> accountsWithStatusIn(Collection<PaymentStatus> statuses);
}
