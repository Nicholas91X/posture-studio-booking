package com.posturestudio.booking.auth;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(Email email);

    // the account of a verified number: only verified numbers are written (V8, guide 29, 2.4)
    Optional<AppUser> findByPhone(String phone);

    boolean existsByEmail(Email email);

    // only the ids: no entity is loaded, only one column comes from the database
    @Query("select u.id from AppUser u where u.id in :ids and u.active = true")
    Set<Long> findActiveIdsAmong(@Param("ids") Collection<Long> ids);

    // The first admin (guide 26, 2.5). "Check, then insert" has a race between the two, if two instances
    // start together: the database closes it. With the email taken nothing is written and nothing fails;
    // 1 if the row was written, 0 if not. The email arrives already normalized (D-020)
    @Modifying
    @Query(value = "insert into app_user (role, email) values ('ADMIN', :email) on conflict (email) do nothing",
            nativeQuery = true)
    int insertAdminIfAbsent(@Param("email") String email);

}
