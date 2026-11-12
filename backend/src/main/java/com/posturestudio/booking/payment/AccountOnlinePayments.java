package com.posturestudio.booking.payment;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.posturestudio.booking.practitioner.OnlinePayments;

/**
 * practitioner's OnlinePayments (D-084): a practitioner can be paid online when an account is assigned to them, the
 * same question StripeCardCheckout.available asks for one practitioner at the request (guide 30, part 6), here for a
 * whole list in one query (D-029).
 */
@Component
class AccountOnlinePayments implements OnlinePayments {

    private final PaymentAccountAssignmentRepository assignments;
    private final PaymentAccounts accounts;

    AccountOnlinePayments(PaymentAccountAssignmentRepository assignments, PaymentAccounts accounts) {
        this.assignments = assignments;
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> availableFor(Collection<Long> practitionerIds) {
        if (practitionerIds.isEmpty()) {
            return Set.of();
        }
        // an assignment always names a configured account (checked when assigned and at startup): the filter is the
        // same belt and braces as forNewPayment
        return assignments.findAllById(practitionerIds).stream()
                .filter(assignment -> accounts.byName(assignment.getAccount()).isPresent())
                .map(PaymentAccountAssignment::getPractitionerId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
