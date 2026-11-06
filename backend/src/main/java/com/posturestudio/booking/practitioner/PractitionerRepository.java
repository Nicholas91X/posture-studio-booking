package com.posturestudio.booking.practitioner;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface PractitionerRepository extends JpaRepository<Practitioner, Long> {

    List<Practitioner> findAllByOrderByLastNameAscFirstNameAsc();

}
