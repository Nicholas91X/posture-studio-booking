package com.posturestudio.booking.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ServiceOfferRepository extends JpaRepository<ServiceOffer, Long> {

    // only the ids of the evaluations among these services (D-043): one column, one query
    @Query("select s.id from ServiceOffer s where s.id in :ids and s.evaluation = true")
    Set<Long> findEvaluationIdsAmong(Collection<Long> ids);

    // ownership is part of the query: someone else's service and a missing one look the same
    Optional<ServiceOffer> findByIdAndPractitionerId(Long id, Long practitionerId);

    // panel: deactivated ones too, in creation order
    List<ServiceOffer> findByPractitionerIdOrderById(Long practitionerId);

    // public catalog
    List<ServiceOffer> findByPractitionerIdAndActiveTrueOrderById(Long practitionerId);

    // one service on sale: ownership and state in the query
    Optional<ServiceOffer> findByIdAndPractitionerIdAndActiveTrue(Long id, Long practitionerId);
}
