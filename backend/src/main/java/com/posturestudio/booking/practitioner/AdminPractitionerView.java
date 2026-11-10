package com.posturestudio.booking.practitioner;

import com.posturestudio.booking.auth.AccountSummary;

/**
 * A practitioner as the admin sees it (guide 26, part 7): with the email and the state of the account,
 * which come from auth. A record of its own, as PublicPractitioner: what is for the admin never reaches
 * the public API by accident.
 */
public record AdminPractitionerView(long id, String email, String firstName, String lastName, boolean active) {

    static AdminPractitionerView from(Practitioner practitioner, AccountSummary account) {
        return new AdminPractitionerView(practitioner.getId(), account.email(),
                practitioner.getFirstName(), practitioner.getLastName(), account.active());
    }
}
