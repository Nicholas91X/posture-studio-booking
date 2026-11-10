package com.posturestudio.booking.payment;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PaymentAccountAssignmentRepository extends JpaRepository<PaymentAccountAssignment, Long> {

    List<PaymentAccountAssignment> findAllByOrderByPractitionerId();

    @Query("select distinct a.account from PaymentAccountAssignment a")
    List<String> assignedAccounts();
}
