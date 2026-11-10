package com.posturestudio.booking.practitioner;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface PractitionerRepository extends JpaRepository<Practitioner, Long> {

    List<Practitioner> findAllByOrderByLastNameAscFirstNameAsc();

    // practitioner.user_id is unique (V1): at most one. For the signed-in practitioner (guide 25, 7.2)
    Optional<Practitioner> findByUserId(Long userId);

}
