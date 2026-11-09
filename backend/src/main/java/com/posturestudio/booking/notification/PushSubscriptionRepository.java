package com.posturestudio.booking.notification;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    List<PushSubscription> findByPractitionerIdOrderByRegisteredAtDesc(Long practitionerId);

    // Insert or update in one statement (guide 28, 6.2): the same browser registers again at every visit,
    // and may now be used by another practitioner. "excluded" is the row that was going to be inserted.
    // Two registrations of the same endpoint at the same time cannot make two rows
    @Modifying
    @Query(value = """
            insert into push_subscription (practitioner_id, endpoint, p256dh, auth)
            values (:practitionerId, :endpoint, :p256dh, :auth)
            on conflict (endpoint) do update
                set practitioner_id = excluded.practitioner_id,
                    p256dh = excluded.p256dh,
                    auth = excluded.auth,
                    registered_at = now()
            """, nativeQuery = true)
    int upsert(@Param("practitionerId") long practitionerId, @Param("endpoint") String endpoint,
               @Param("p256dh") String p256dh, @Param("auth") String auth);

    // past the limit, the devices registered longest ago go (guide 28, 6.3)
    @Modifying
    @Query(value = """
            delete from push_subscription
            where practitioner_id = :practitionerId
              and id not in (select id from push_subscription where practitioner_id = :practitionerId
                             order by registered_at desc, id desc limit :keep)
            """, nativeQuery = true)
    int keepNewest(@Param("practitionerId") long practitionerId, @Param("keep") int keep);

    // only an own device (guide 28, 6.4): another practitioner's endpoint is not touched
    @Modifying
    @Query("delete from PushSubscription s where s.endpoint = :endpoint and s.practitionerId = :practitionerId")
    int deleteOwn(@Param("practitionerId") long practitionerId, @Param("endpoint") String endpoint);
}
