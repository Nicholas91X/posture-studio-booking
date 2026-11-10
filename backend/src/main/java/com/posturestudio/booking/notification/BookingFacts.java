package com.posturestudio.booking.notification;

import com.posturestudio.booking.customer.CustomerContact;
import com.posturestudio.booking.practitioner.PractitionerContact;

/**
 * What an email about a booking needs and the event does not carry (guide 27, 5.4): each piece asked to
 * the module that owns it, after the commit.
 */
record BookingFacts(CustomerContact customer, PractitionerContact practitioner, String serviceName) {
}
