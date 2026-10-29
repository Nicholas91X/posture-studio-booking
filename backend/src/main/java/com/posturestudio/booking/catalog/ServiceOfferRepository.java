package com.posturestudio.booking.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface ServiceOfferRepository extends JpaRepository<ServiceOffer, Long> {

    // ownership is part of the query: someone else's service and a missing one look the same
    Optional<ServiceOffer> findByIdAndPractitionerId(Long id, Long practitionerId);

    // panel: deactivated ones too, in creation order
    List<ServiceOffer> findByPractitionerIdOrderById(Long practitionerId);

    // public catalog
    List<ServiceOffer> findByPractitionerIdAndActiveTrueOrderById(Long practitionerId);
}
