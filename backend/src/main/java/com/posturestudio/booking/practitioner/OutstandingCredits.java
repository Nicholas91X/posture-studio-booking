package com.posturestudio.booking.practitioner;

import java.util.List;

/**
 * The credits still valid of the customers of a practitioner (D-077, guide 45, part 6): the warning the admin gets
 * after a deactivation. Declared here and implemented by the credit module, which already depends on practitioner.
 */
public interface OutstandingCredits {

    // the lots of this practitioner with credits left and the last valid day not gone (today in Rome)
    List<OutstandingCredit> validOf(long practitionerId);
}
