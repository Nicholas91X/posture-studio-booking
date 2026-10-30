package com.posturestudio.booking.practitioner;

import org.springframework.stereotype.Component;

import com.posturestudio.booking.shared.NotAuthenticatedException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * PROVISIONAL (D-028). TODO (step 8): remove this class and implement CurrentPractitioner
 * from the authenticated user (practitioner.user_id).
 *
 * The identity is DECLARED by the client in a header, not verified: it must never reach production.
 */
@Component
class DevHeaderCurrentPractitioner implements CurrentPractitioner {

    static final String HEADER = "X-Practitioner-Id";

    // a proxy: each call reaches the request bound to the current thread
    private final HttpServletRequest request;
    private final PractitionerRepository repository;

    DevHeaderCurrentPractitioner(HttpServletRequest request, PractitionerRepository repository) {
        this.request = request;
        this.repository = repository;
    }

    // no @Transactional needed: existsById of Spring Data is already transactional
    @Override
    public long id() {
        String value = request.getHeader(HEADER);
        try {
            long id = Long.parseLong(value);
            if (repository.existsById(id)) {
                return id;
            }
        } catch (NumberFormatException e) {
            // missing or not a number: same answer as an unknown id
        }
        throw new NotAuthenticatedException("No current practitioner");
    }
}
