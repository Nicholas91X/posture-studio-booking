package com.posturestudio.booking.catalog;

import static com.posturestudio.booking.shared.Checks.requireNotBlank;
import static com.posturestudio.booking.shared.Checks.requirePositive;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.Generated;
import jakarta.persistence.*;

// A service offered by a practitioner. Named ServiceOffer, not Service, to avoid the clash
// with Spring's @Service annotation; the table keeps its name.
// Package-private class: other modules use CatalogService (D-002, D-026).
// Public methods: PackageOffer reaches this entity through a lazy proxy, and a Hibernate proxy
// is guaranteed to intercept (and load) only non-private methods it can override.
@Entity
@Table(name = "service")
class ServiceOffer {

    protected ServiceOffer() {
        // JPA needs a no-arg constructor
    }

    ServiceOffer(Long practitionerId, ServiceOfferDetails details) {
        this.practitionerId = Objects.requireNonNull(practitionerId, "practitionerId must not be null");
        changeDetails(details);
        this.active = true;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // practitioner belongs to another module: only the id (D-026)
    @Column(updatable = false)
    private Long practitionerId;

    @Column
    private String name;

    @Column
    private int durationMinutes;

    @Column
    private long priceCents;

    @Column
    private boolean active;

    @Column(updatable = false)
    @Generated
    private Instant createdAt;

    // ***** METHODS *****

    // Changing price or duration never alters what was already booked or sold:
    // bookings and credit lots keep their own copy of the price (D-011),
    // and every booking stores its own start and end
    // all values are checked before any field changes: an invalid change leaves the entity as it was
    public void changeDetails(ServiceOfferDetails details) {
        Objects.requireNonNull(details, "details must not be null");
        String newName = requireNotBlank(details.name(), "name");
        int newDuration = requirePositive(details.durationMinutes(), "durationMinutes");
        long newPrice = requirePositive(details.priceCents(), "priceCents");
        this.name = newName;
        this.durationMinutes = newDuration;
        this.priceCents = newPrice;
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

    public String getName() {
        return name;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
