package com.posturestudio.booking.customer;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

interface CustomerRepository extends JpaRepository<Customer, Long> {

    // select ... for update (PostgreSQL: "for no key update"): the row stays locked until the end of
    // the transaction, and whoever asks for the same lock waits (guide 19, part 4).
    // The word between "find" and "By" is only a name: Spring Data ignores it
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Customer> findLockedById(Long id);
}
