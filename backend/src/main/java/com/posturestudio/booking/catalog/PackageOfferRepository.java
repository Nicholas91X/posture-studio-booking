package com.posturestudio.booking.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PackageOfferRepository extends JpaRepository<PackageOffer, Long> {

    // ownership is part of the query (see ServiceOfferRepository)
    Optional<PackageOffer> findByIdAndPractitionerId(Long id, Long practitionerId);

    // panel: deactivated ones too. join fetch loads the service in the same query (no N+1)
    @Query("""
            select p from PackageOffer p join fetch p.service
            where p.practitionerId = :practitionerId
            order by p.id
            """)
    List<PackageOffer> findForPanel(Long practitionerId);

    // public catalog: a package is on sale only if it is active AND its service is active.
    // A filter on read, no cascade on deactivation
    @Query("""
            select p from PackageOffer p join fetch p.service s
            where p.practitionerId = :practitionerId and p.active = true and s.active = true
            order by p.id
            """)
    List<PackageOffer> findOnSale(Long practitionerId);
}
