package com.posturestudio.booking.payment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.PractitionerService;
import com.posturestudio.booking.shared.BusinessRuleException;
import com.posturestudio.booking.shared.ErrorCode;
import com.posturestudio.booking.shared.NotFoundException;

/**
 * Which account each practitioner collects on (D-057, guide 30, parts 5 and 9). The admin assigns;
 * the payments of piece 2 ask forNewPayment, once, when they are created.
 */
@Service
class PaymentAccountService {

    private final PaymentAccounts accounts;
    private final PaymentAccountAssignmentRepository assignments;
    private final PractitionerService practitioners;
    private final Clock clock;

    PaymentAccountService(PaymentAccounts accounts, PaymentAccountAssignmentRepository assignments,
                          PractitionerService practitioners, Clock clock) {
        this.accounts = accounts;
        this.assignments = assignments;
        this.practitioners = practitioners;
        this.clock = clock;
    }

    // The account a NEW payment of this practitioner is made on, or empty: no account, no card payments
    // (guide 30, part 6). Asked once, when the payment is created, and copied into it (part 8): every later
    // operation on that payment uses PaymentAccounts.of(payment), never this
    @Transactional(readOnly = true)
    Optional<PaymentAccount> forNewPayment(long practitionerId) {
        // an assignment always names a configured account: checked when assigned and at startup
        return assignments.findById(practitionerId).flatMap(assignment -> accounts.byName(assignment.getAccount()));
    }

    // ***** ADMIN *****

    // every configured account, in the order of PAYMENT_ACCOUNTS, with who uses it; names only, never a key
    @Transactional(readOnly = true)
    List<PaymentAccountView> listAccounts() {
        Map<String, List<Long>> practitionersByAccount = assignments.findAllByOrderByPractitionerId().stream()
                .collect(Collectors.groupingBy(PaymentAccountAssignment::getAccount,
                        Collectors.mapping(PaymentAccountAssignment::getPractitionerId, Collectors.toList())));
        return accounts.names().stream()
                .map(name -> new PaymentAccountView(name, practitionersByAccount.getOrDefault(name, List.of())))
                .toList();
    }

    // 404 for a practitioner that does not exist and for one with no account: there is nothing to show
    @Transactional(readOnly = true)
    AssignedAccountView assignedTo(long practitionerId) {
        practitioners.requireExists(practitionerId);
        return assignments.findById(practitionerId)
                .map(AssignedAccountView::from)
                .orElseThrow(() -> new NotFoundException("Practitioner " + practitionerId + " has no payment account"));
    }

    // Assigning moves only the payments created from now on (D-057). Idempotent: the same account again
    // changes nothing (guide 30, 9.2). A deactivated practitioner can be assigned too
    @Transactional
    AssignedAccountView assign(long practitionerId, String accountName) {
        practitioners.requireExists(practitionerId);
        PaymentAccount account = accounts.byName(accountName).orElseThrow(() -> new BusinessRuleException(
                ErrorCode.PAYMENT_ACCOUNT_UNKNOWN, "Payment account '" + accountName + "' is not configured"));
        PaymentAccountAssignment assignment = assignments.findById(practitionerId)
                .map(existing -> {
                    existing.reassign(account, clock.instant());
                    return existing;
                })
                // a single admin: two first assignments at the same instant for the same practitioner would
                // meet the primary key, and the second would fail. Not worth an upsert here
                .orElseGet(() -> assignments.save(new PaymentAccountAssignment(practitionerId, account, clock.instant())));
        return AssignedAccountView.from(assignment);
    }
}
