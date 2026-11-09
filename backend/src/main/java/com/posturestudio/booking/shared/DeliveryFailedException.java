package com.posturestudio.booking.shared;

/**
 * A message that had to reach a person now (an access code) could not be sent: the mail server did not
 * answer. Becomes a 503: the request was right, try again in a moment (guide 24, 7.5).
 */
public class DeliveryFailedException extends RuntimeException {

    public DeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
