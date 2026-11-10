package com.posturestudio.booking.customer;

import static com.posturestudio.booking.shared.Checks.requireNotBlank;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A customer of the studio (V1). Package-private: other modules use CustomerService (D-002, D-026).
// Created from the panel (D-024) and, at step 8, at the first sign-in (D-014)
@Entity
@Table(name = "customer")
class Customer {

    protected Customer() {
        // JPA needs a no-arg constructor
    }

    Customer(Long userId, String firstName, String lastName) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.firstName = requireNotBlank(firstName, "firstName");
        this.lastName = requireNotBlank(lastName, "lastName");
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // app_user belongs to the auth module: only the id, never the entity (D-026)
    @Column(updatable = false)
    private Long userId;

    @Column
    private String firstName;

    @Column
    private String lastName;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // the customer corrects their own names from the profile (guide 25, 7.3)
    void rename(String firstName, String lastName) {
        this.firstName = requireNotBlank(firstName, "firstName");
        this.lastName = requireNotBlank(lastName, "lastName");
    }


    Long getId() {
        return id;
    }

    Long getUserId() {
        return userId;
    }

    String getFirstName() {
        return firstName;
    }

    String getLastName() {
        return lastName;
    }
}
