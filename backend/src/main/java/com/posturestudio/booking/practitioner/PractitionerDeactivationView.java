package com.posturestudio.booking.practitioner;

import java.util.List;

/**
 * The answer of a deactivation (D-077, guide 45, part 6): the practitioner after it, and as a warning the credits
 * still valid of their customers, which the system does not move. What to do with them is decided outside the
 * system (a refund follows docs/procedure-studio.md).
 */
public record PractitionerDeactivationView(AdminPractitionerView practitioner,
                                           List<OutstandingCredit> outstandingCredits) {
}
