package com.posturestudio.booking.practitioner;

import java.util.Collection;
import java.util.Set;

/**
 * Whether a customer can pay a practitioner online (step 12, D-084), in practitioner's own words. Declared here and
 * implemented by the payment module, which owns the payment accounts and already depends on practitioner: the
 * dependency stays one way, as FutureBookings and OutstandingCredits (D-081).
 */
public interface OnlinePayments {

    // the ids, among these, of the practitioners with a payment account (D-057): one question for a whole list (D-029)
    Set<Long> availableFor(Collection<Long> practitionerIds);
}
