package com.posturestudio.booking.customer;

import java.time.Instant;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A customer of the studio (V1). Package-private: other modules use CustomerService (D-002, D-026).
// No public constructor yet: customers are created at the first sign-in (step 8) or from the panel (piece 5)
@Entity
@Table(name = "customer")
class Customer {

    protected Customer() {
        // JPA needs a no-arg constructor
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

    Long getId() {
        return id;
    }
}
