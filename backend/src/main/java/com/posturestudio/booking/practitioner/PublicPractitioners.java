package com.posturestudio.booking.practitioner;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public list of the practitioners (D-029, D-084), with whether each can be paid online. A service of its own, as
 * PractitionerDeactivation (D-081): PractitionerService stays usable without the payment module, which implements
 * OnlinePayments and depends on practitioner (the many test contexts that import PractitionerService alone).
 */
@Service
class PublicPractitioners {

    private final PractitionerService practitioners;
    private final OnlinePayments onlinePayments;

    PublicPractitioners(PractitionerService practitioners, OnlinePayments onlinePayments) {
        this.practitioners = practitioners;
        this.onlinePayments = onlinePayments;
    }

    // Three queries whatever the number of practitioners: all of them, their accounts (auth), their payment accounts
    @Transactional(readOnly = true)
    List<PublicPractitioner> listActive() {
        List<Practitioner> active = practitioners.activeInOrder();
        Set<Long> online = onlinePayments.availableFor(active.stream().map(Practitioner::getId).toList());
        return active.stream()
                .map(practitioner -> PublicPractitioner.from(practitioner, online.contains(practitioner.getId())))
                .toList();
    }
}
