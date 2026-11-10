package com.posturestudio.booking.payment;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// The account a practitioner collects on, chosen by the admin (V10, guide 30, part 5). It decides the
// account of NEW payments only: existing payments keep their own (D-057)
@Entity
@Table(name = "practitioner_payment_account")
class PaymentAccountAssignment {

    protected PaymentAccountAssignment() {
        // JPA needs a no-arg constructor
    }

    PaymentAccountAssignment(long practitionerId, PaymentAccount account, Instant now) {
        this.practitionerId = practitionerId;
        this.account = Objects.requireNonNull(account, "account must not be null").name();
        this.assignedAt = Objects.requireNonNull(now, "now must not be null");
    }

    // the id of the practitioner, not generated: one row per practitioner at most
    @Id
    private Long practitionerId;

    @Column
    private String account;

    @Column
    private Instant assignedAt;

    // Idempotent (guide 30, 9.2): the same account again changes nothing, assignedAt included
    void reassign(PaymentAccount newAccount, Instant now) {
        Objects.requireNonNull(newAccount, "newAccount must not be null");
        if (newAccount.name().equals(account)) {
            return;
        }
        account = newAccount.name();
        assignedAt = Objects.requireNonNull(now, "now must not be null");
    }

    Long getPractitionerId() {
        return practitionerId;
    }

    String getAccount() {
        return account;
    }

    Instant getAssignedAt() {
        return assignedAt;
    }
}
