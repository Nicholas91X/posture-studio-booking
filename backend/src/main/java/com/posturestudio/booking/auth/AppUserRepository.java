package com.posturestudio.booking.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(Email email);

    boolean existsByEmail(Email email);

}