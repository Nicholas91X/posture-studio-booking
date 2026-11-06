package com.posturestudio.booking.catalog;

import static com.posturestudio.booking.shared.Checks.requireAtLeast;
import static com.posturestudio.booking.shared.Checks.requireNotBlank;
import static com.posturestudio.booking.shared.Checks.requirePositive;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A package on sale. A purchased package is not stored here: it becomes a credit lot (D-010).
// Package-private class, public methods (see ServiceOffer).
@Entity
@Table(name = "package_offer")
class PackageOffer {

    protected PackageOffer() {
        // JPA needs a no-arg constructor
    }

    PackageOffer(Long practitionerId, ServiceOffer service, PackageOfferDetails details) {
        this.practitionerId = Objects.requireNonNull(practitionerId, "practitionerId must not be null");
        this.service = Objects.requireNonNull(service, "service must not be null");
        changeDetails(details);
        this.active = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // practitioner belongs to another module: only the id (D-026).
    // The composite foreign key of V2 keeps it equal to the practitioner of the service (D-025)
    @Column(updatable = false)
    private Long practitionerId;

    // same module: a real relation. LAZY because the JPA default for @ManyToOne is EAGER.
    // Never changed after creation: a package on another service is another package
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", updatable = false)
    private ServiceOffer service;

    @Column
    private String name;

    @Column
    private int sessionsCount;

    // price of the whole package. Changing it never alters packages already sold:
    // a credit lot keeps its own copy (D-011)
    @Column
    private long priceCents;

    @Column
    private int validityDays;

    @Column
    private boolean active;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // all values are checked before any field changes: an invalid change leaves the entity as it was
    public void changeDetails(PackageOfferDetails details) {
        Objects.requireNonNull(details, "details must not be null");
        String newName = requireNotBlank(details.name(), "name");
        // a package of one session would be a single session sold in a different way
        int newSessions = requireAtLeast(details.sessionsCount(), 2, "sessionsCount");
        long newPrice = requirePositive(details.priceCents(), "priceCents");
        int newValidity = requirePositive(details.validityDays(), "validityDays");
        this.name = newName;
        this.sessionsCount = newSessions;
        this.priceCents = newPrice;
        this.validityDays = newValidity;
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public Long getPractitionerId() {
        return practitionerId;
    }

    public ServiceOffer getService() {
        return service;
    }

    public String getName() {
        return name;
    }

    public int getSessionsCount() {
        return sessionsCount;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public int getValidityDays() {
        return validityDays;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
