package com.posturestudio.booking.practitioner;

/**
 * What anyone sees of a practitioner without signing in: only id and name.
 * A record of its own, not shared with the panel: a field added for the panel must never
 * reach the public API by accident. The frontend composes the name to show.
 */
public record PublicPractitioner(long id, String firstName, String lastName) {

    static PublicPractitioner from(Practitioner practitioner) {
        return new PublicPractitioner(practitioner.getId(), practitioner.getFirstName(), practitioner.getLastName());
    }
}
