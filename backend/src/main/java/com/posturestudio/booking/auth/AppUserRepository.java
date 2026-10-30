package com.posturestudio.booking.auth;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(Email email);

    boolean existsByEmail(Email email);

    // only the ids: no entity is loaded, only one column comes from the database
    @Query("select u.id from AppUser u where u.id in :ids and u.active = true")
    Set<Long> findActiveIdsAmong(@Param("ids") Collection<Long> ids);

}
