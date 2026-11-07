package com.posturestudio.booking.customer;

/**
 * The customer using the site in the current request. Customer controllers depend only on this
 * interface, so that the way the identity is found can change in one place (as D-028 for practitioners).
 */
public interface CurrentCustomer {

    /**
     * @throws com.posturestudio.booking.shared.NotAuthenticatedException if nobody is acting
     */
    long id();
}
