package com.posturestudio.booking.practitioner;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.auth.CurrentUser;
import com.posturestudio.booking.auth.Role;
import com.posturestudio.booking.shared.NotAuthenticatedException;

/**
 * The practitioner of the signed-in user (guide 25, 7.2): replaces DevHeaderCurrentPractitioner, which
 * believed a header written by the client (D-028). The interface did not change, so no controller did.
 * Being active is already checked at every request by auth (SessionAccounts).
 */
@Component
class SessionCurrentPractitioner implements CurrentPractitioner {

    private final CurrentUser user;
    private final PractitionerRepository repository;

    SessionCurrentPractitioner(CurrentUser user, PractitionerRepository repository) {
        this.user = user;
        this.repository = repository;
    }

    // The URL rules already keep other roles out of /api/panel; checked again here because this
    // interface could be used elsewhere. A PRACTITIONER account without its practitioner row should not
    // exist (the admin creates both together, step 8 piece 3): if it does, nobody is acting
    @Override
    public long id() {
        if (user.role() != Role.PRACTITIONER) {
            throw new NotAuthenticatedException("No current practitioner");
        }
        return repository.findByUserId(user.id())
                .map(Practitioner::getId)
                .orElseThrow(() -> new NotAuthenticatedException("No current practitioner"));
    }
}
