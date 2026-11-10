package com.posturestudio.booking.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A device of a practitioner registered for push (V7, guide 28, part 6). Never saved through JPA: rows are
// written by the native upsert of the repository (an insert or an update in one statement), and deleted
@Entity
@Table(name = "push_subscription")
class PushSubscription {

    protected PushSubscription() {
        // JPA needs a no-arg constructor
    }

    @Id
    private Long id;

    // practitioner belongs to another module: only the id (D-026)
    @Column
    private Long practitionerId;

    @Column
    private String endpoint;

    @Column
    private String p256dh;

    @Column
    private String auth;

    @Column
    private Instant registeredAt;

    Long getId() {
        return id;
    }

    Long getPractitionerId() {
        return practitionerId;
    }

    String getEndpoint() {
        return endpoint;
    }

    String getP256dh() {
        return p256dh;
    }

    String getAuth() {
        return auth;
    }
}
