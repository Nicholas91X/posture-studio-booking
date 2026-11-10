package com.posturestudio.booking.practitioner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.auth.AccountService;
import com.posturestudio.booking.auth.AccountSummary;
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

    // Active or not: the admin assigns a payment account to a deactivated practitioner too, who finds it
    // again when reactivated (guide 30, 9.2)
    @Transactional(readOnly = true)
    public void requireExists(long practitionerId) {
        if (!repository.existsById(practitionerId)) {
            throw notFound(practitionerId);
        }
    }

    // ***** NOTIFICATION (guide 27) *****

    // Active or not: an email about a booking already made is still due (guide 27, 5.4)
    @Transactional(readOnly = true)
    public PractitionerContact contactOf(long practitionerId) {
        Practitioner practitioner = find(practitionerId);
        return new PractitionerContact(accounts.emailOf(practitioner.getUserId()), practitioner.getFirstName(),
                practitioner.getLastName());
    }

    // ***** ADMIN (D-021, guide 26) *****

    // Two queries whatever the number of practitioners, as listActive: all of them, then email and state
    // of their accounts in one question to auth (guide 26, part 7)
    @Transactional(readOnly = true)
    public List<AdminPractitionerView> listForAdmin() {
        List<Practitioner> all = repository.findAllByOrderByLastNameAscFirstNameAsc();
        Map<Long, AccountSummary> accountsById = accounts.summariesOf(all.stream().map(Practitioner::getUserId).toList());
        return all.stream()
                .map(practitioner -> AdminPractitionerView.from(practitioner, accountsById.get(practitioner.getUserId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminPractitionerView getForAdmin(long practitionerId) {
        return adminView(find(practitionerId));
    }

    // Two rows, born together or not at all (guide 26, part 4): AccountService joins this transaction, so
    // a failure of the second insert rolls back the account too. The settings start with the defaults of
    // the database; no working hours yet, so nobody can book until the practitioner sets them
    @Transactional
    public AdminPractitionerView create(NewPractitioner request) {
        long userId = accounts.createPractitionerAccount(request.email());
        Practitioner practitioner = repository.save(new Practitioner(userId, request.firstName(), request.lastName()));
        return adminView(practitioner);
    }

    @Transactional
    public AdminPractitionerView rename(long practitionerId, PractitionerNames names) {
        Practitioner practitioner = find(practitionerId);
        practitioner.rename(names.firstName(), names.lastName());
        return adminView(practitioner);
    }

    // Only the account (D-029): sign-in, open sessions, public list, catalog, free slots and new bookings all
    // follow from it. Bookings already made are not touched (guide 26, 6.3): pending approvals expire by
    // themselves, confirmed ones are a cancellation by the studio (open point, before step 11)
    @Transactional
    public AdminPractitionerView deactivate(long practitionerId) {
        Practitioner practitioner = find(practitionerId);
        accounts.deactivate(practitioner.getUserId());
        return adminView(practitioner);
    }

    // the only way back for a practitioner: a code never reactivates an account (D-050)
    @Transactional
    public AdminPractitionerView activate(long practitionerId) {
        Practitioner practitioner = find(practitionerId);
        accounts.activate(practitioner.getUserId());
        return adminView(practitioner);
    }

    // ***** HELPERS *****

    // the account was just changed in this same transaction: auth reads it from the same persistence
    // context, so the answer is the state after the change, not the one in the database before the commit
    private AdminPractitionerView adminView(Practitioner practitioner) {
        AccountSummary account = accounts.summariesOf(List.of(practitioner.getUserId())).get(practitioner.getUserId());
        return AdminPractitionerView.from(practitioner, account);
    }

    private Practitioner find(long practitionerId) {
        return repository.findById(practitionerId).orElseThrow(() -> notFound(practitionerId));
    }

    private static NotFoundException notFound(long practitionerId) {
        return new NotFoundException("Practitioner " + practitionerId + " not found");
    }
}
