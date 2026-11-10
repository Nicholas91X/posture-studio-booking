package com.posturestudio.booking.payment;

import java.time.Instant;

// The account of a practitioner, for the admin (guide 30, 9.1)
record AssignedAccountView(String account, Instant assignedAt) {

    static AssignedAccountView from(PaymentAccountAssignment assignment) {
        return new AssignedAccountView(assignment.getAccount(), assignment.getAssignedAt());
    }
}
