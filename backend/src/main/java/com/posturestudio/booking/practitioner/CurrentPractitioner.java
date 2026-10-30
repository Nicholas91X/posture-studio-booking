package com.posturestudio.booking.practitioner;

/**
 * The practitioner using the panel in the current request. Panel controllers depend only on this
 * interface, so that the way the identity is found can change in one place (D-028).
 */
public interface CurrentPractitioner {

    /**
     * @throws com.posturestudio.booking.shared.NotAuthenticatedException if nobody is acting
     */
    long id();
}
