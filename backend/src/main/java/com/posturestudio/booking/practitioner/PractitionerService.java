package com.posturestudio.booking.practitioner;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * The only door of the practitioner module (D-002): returns records, never the entity.
 * "Active" is the account's state, which belongs to auth: asked to AccountService (D-029).
 */
@Service
public class PractitionerService {

    private final PractitionerRepository repository;
    private final AccountService accounts;

    PractitionerService(PractitionerRepository repository, AccountService accounts) {
        this.repository = repository;
        this.accounts = accounts;
    }

    // ***** SETTINGS (panel) *****

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

    // ***** PUBLIC *****

    // Two queries whatever the number of practitioners: all of them, then one question to auth
    @Transactional(readOnly = true)
    public List<PublicPractitioner> listActive() {
        List<Practitioner> all = repository.findAllByOrderByLastNameAscFirstNameAsc();
        Set<Long> activeUsers = accounts.activeAmong(all.stream().map(Practitioner::getUserId).toList());
        // filter keeps the order of the query
        return all.stream()
                .filter(practitioner -> activeUsers.contains(practitioner.getUserId()))
                .map(PublicPractitioner::from)
                .toList();
    }

    // Missing and deactivated throw the same exception: a public 404 must not tell them apart
    @Transactional(readOnly = true)
    public void requireActive(long practitionerId) {
        Practitioner practitioner = find(practitionerId);
        if (!accounts.activeAmong(List.of(practitioner.getUserId())).contains(practitioner.getUserId())) {
            throw notFound(practitionerId);
        }
    }

    // ***** HELPERS *****

    private Practitioner find(long practitionerId) {
        return repository.findById(practitionerId).orElseThrow(() -> notFound(practitionerId));
    }

    private static NotFoundException notFound(long practitionerId) {
        return new NotFoundException("Practitioner " + practitionerId + " not found");
    }
}
