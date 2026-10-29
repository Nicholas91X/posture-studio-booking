package com.posturestudio.booking.practitioner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.shared.NotFoundException;

/**
 * The only door of the practitioner module (D-002): returns records, never the entity.
 */
@Service
public class PractitionerService {

    private final PractitionerRepository repository;

    PractitionerService(PractitionerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PractitionerSettings getSettings(long practitionerId) {
        return find(practitionerId).settings();
    }

    @Transactional
    public PractitionerSettings updateSettings(long practitionerId, PractitionerSettings settings) {
        Practitioner practitioner = find(practitionerId);
        practitioner.changeSettings(settings);
        // no save(): the entity is managed, Hibernate writes the update at commit (dirty checking)
        return practitioner.settings();
    }

    private Practitioner find(long practitionerId) {
        return repository.findById(practitionerId)
                .orElseThrow(() -> new NotFoundException("Practitioner " + practitionerId + " not found"));
    }
}
